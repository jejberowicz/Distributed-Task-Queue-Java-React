package com.inferqueue.worker;

import com.inferqueue.config.InferQueueProperties;
import com.inferqueue.domain.Job;
import com.inferqueue.domain.JobStatus;
import com.inferqueue.metrics.QueueMetrics;
import com.inferqueue.queue.DelayedQueue;
import com.inferqueue.queue.JobQueue;
import com.inferqueue.queue.QueueMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public JobExecutor(JobStateService state, JobQueue queue, DelayedQueue delayedQueue, ModelAdapter adapter,
                       InferQueueProperties props, QueueMetrics metrics) {
        this.state = state;
        this.queue = queue;
        this.delayedQueue = delayedQueue;
        this.adapter = adapter;
        this.props = props;
        this.metrics = metrics;
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

        state.markProcessing(job.getId(), workerId, recordId.getValue());
        long startedAt = System.nanoTime();
        try {
            InferenceResult result = adapter.infer(InferenceRequest.from(job));
            state.markDone(job.getId(), result.output(), result.tokensUsed());
            metrics.recordCompleted(System.nanoTime() - startedAt, result.tokensUsed());
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
            state.markRetrying(job.getId(), error);
            // Se ackea el mensaje actual y se programa uno nuevo con delay: el
            // reintento no debe quedar en el PEL bloqueando al reclaimer.
            settle(stream, recordId);
            delayedQueue.scheduleRetry(message.nextAttempt());
            metrics.recordRetry();
            log.warn("Job {} falló (intento {}/{}): {}", job.getId(), nextAttempt, props.queue().maxRetries(), error);
        } else {
            state.markDead(job.getId(), error);
            queue.toDeadLetter(message, error);
            settle(stream, recordId);
            metrics.recordDead();
        }
    }

    /** XACK + borrado del mensaje del stream. */
    private void settle(String stream, RecordId recordId) {
        queue.ack(stream, recordId);
        queue.delete(stream, recordId);
    }

    /** Marca EXPIRED los jobs que superaron su TTL sin llegar a estado terminal. */
    public int sweepExpired() {
        var expired = state.findExpired(Instant.now());
        for (Job job : expired) {
            if (job.getStatus() != JobStatus.EXPIRED) {
                state.markExpired(job.getId());
                metrics.recordExpired();
            }
        }
        if (!expired.isEmpty()) {
            log.info("TTL: {} jobs marcados como EXPIRED", expired.size());
        }
        return expired.size();
    }

    private String truncate(String message) {
        if (message == null) {
            return "error desconocido";
        }
        return message.length() <= MAX_ERROR_LENGTH ? message : message.substring(0, MAX_ERROR_LENGTH);
    }
}
