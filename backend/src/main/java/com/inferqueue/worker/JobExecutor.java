package com.inferqueue.worker;

import com.inferqueue.config.InferQueueProperties;
import com.inferqueue.domain.Job;
import com.inferqueue.metrics.QueueMetrics;
import com.inferqueue.queue.DelayedQueue;
import com.inferqueue.queue.JobQueue;
import com.inferqueue.queue.QueueMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

/**
 * Procesa un mensaje de la cola de punta a punta. Es el lugar donde se resuelve
 * el contrato at-least-once: el XACK ocurre siempre al final, después de haber
 * dejado el job en un estado consistente en PostgreSQL.
 */
@Component
public class JobExecutor {

    private static final Logger log = LoggerFactory.getLogger(JobExecutor.class);
    private static final int MAX_ERROR_LENGTH = 2000;

    private final JobStateService state;
    private final JobQueue queue;
    private final DelayedQueue delayedQueue;
    private final ModelAdapter adapter;
    private final InferQueueProperties props;
    private final QueueMetrics metrics;
    private final ApplicationEventPublisher events;

    public JobExecutor(JobStateService state, JobQueue queue, DelayedQueue delayedQueue, ModelAdapter adapter,
                       InferQueueProperties props, QueueMetrics metrics, ApplicationEventPublisher events) {
        this.state = state;
        this.queue = queue;
        this.delayedQueue = delayedQueue;
        this.adapter = adapter;
        this.props = props;
        this.metrics = metrics;
        this.events = events;
    }

    public void process(String stream, RecordId recordId, QueueMessage message, String workerId) {
        Optional<Job> maybeJob = state.find(message.jobId());
        if (maybeJob.isEmpty()) {
            log.warn("Mensaje {} apunta a un job inexistente ({}), lo descarto", recordId, message.jobId());
            settle(stream, recordId);
            return;
        }

        Job job = maybeJob.get();

        // At-least-once significa que un job puede llegar dos veces (por ejemplo
        // si el worker murió justo después de escribir el resultado pero antes
        // del XACK). Si ya está terminado, ackeamos y listo: la reentrega es un no-op.
        if (job.getStatus().isTerminal()) {
            log.debug("Job {} ya estaba en estado {}, ignoro la reentrega", job.getId(), job.getStatus());
            settle(stream, recordId);
            return;
        }

        if (job.isExpired(Instant.now())) {
            state.markExpired(job.getId());
            metrics.recordExpired();
            settle(stream, recordId);
            return;
        }

        if (state.markProcessing(job.getId(), workerId, recordId.getValue()).isEmpty()) {
            // Se cerró entre el chequeo de arriba y el lock (cancelación o TTL).
            log.debug("Job {} dejó de estar disponible antes de arrancar, lo ackeo", job.getId());
            settle(stream, recordId);
            return;
        }

        long startedAt = System.nanoTime();
        TokenStream tokens = new TokenStream(job, events);
        try {
            InferenceResult result = adapter.infer(InferenceRequest.from(job), tokens);
            // Lo que quedó en el buffer se emite antes del evento de DONE, así el
            // dashboard no ve el resultado final antes que el último fragmento.
            tokens.flush();
            // Si volvió vacío es que lo cancelaron durante la inference: el
            // resultado se descarta, el estado del cliente manda.
            if (state.markDone(job.getId(), result.output(), result.tokensUsed()).isPresent()) {
                metrics.recordCompleted(System.nanoTime() - startedAt, result.tokensUsed());
            } else {
                log.info("Job {} se canceló durante la inference; descarto el resultado", job.getId());
            }
            settle(stream, recordId);
        } catch (InferenceException e) {
            handleFailure(stream, recordId, message, job, e);
        } catch (RuntimeException e) {
            handleFailure(stream, recordId, message, job,
                    new InferenceException("Error inesperado: " + e.getMessage(), e, false));
        }
    }

    private void handleFailure(String stream, RecordId recordId, QueueMessage message, Job job, InferenceException e) {
        String error = truncate(e.getMessage());
        int nextAttempt = message.attempt() + 1;
        boolean canRetry = e.isRetryable() && nextAttempt <= props.queue().maxRetries();

        if (canRetry) {
            // Un job cancelado no se reintenta: si la transición no prosperó, el
            // job ya está cerrado y sólo queda sacar el mensaje de la cola.
            if (state.markRetrying(job.getId(), error).isEmpty()) {
                settle(stream, recordId);
                return;
            }
            // Se ackea el mensaje actual y se programa uno nuevo con delay: el
            // reintento no debe quedar en el PEL bloqueando al reclaimer.
            settle(stream, recordId);
            delayedQueue.scheduleRetry(message.nextAttempt());
            metrics.recordRetry();
            log.warn("Job {} falló (intento {}/{}): {}", job.getId(), nextAttempt, props.queue().maxRetries(), error);
        } else {
            if (state.markDead(job.getId(), error).isPresent()) {
                queue.toDeadLetter(message, error);
                metrics.recordDead();
            }
            settle(stream, recordId);
        }
    }

    /** XACK + borrado del mensaje del stream. */
    private void settle(String stream, RecordId recordId) {
        queue.ack(stream, recordId);
        queue.delete(stream, recordId);
    }

    /** Marca EXPIRED los jobs que superaron su TTL sin llegar a estado terminal. */
    public int sweepExpired() {
        int swept = 0;
        for (Job job : state.findExpired(Instant.now())) {
            if (state.markExpired(job.getId()).isPresent()) {
                metrics.recordExpired();
                swept++;
            }
        }
        if (swept > 0) {
            log.info("TTL: {} jobs marcados como EXPIRED", swept);
        }
        return swept;
    }

    private String truncate(String message) {
        if (message == null) {
            return "error desconocido";
        }
        return message.length() <= MAX_ERROR_LENGTH ? message : message.substring(0, MAX_ERROR_LENGTH);
    }
}
