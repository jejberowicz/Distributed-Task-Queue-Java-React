package com.inferqueue.api;

import com.inferqueue.config.InferQueueProperties;
import com.inferqueue.domain.Job;
import com.inferqueue.domain.JobEvent;
import com.inferqueue.domain.JobRepository;
import com.inferqueue.domain.JobStatus;
import com.inferqueue.queue.DeadLetterEntry;
import com.inferqueue.queue.JobQueue;
import com.inferqueue.queue.QueueMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Operación sobre la dead-letter queue: mirar qué murió y por qué, y volver a
 * encolarlo cuando la causa era transitoria (el modelo estaba caído, Ollama sin
 * memoria). Sin esto la DLQ es sólo un contador que sube.
 */
@Service
public class DeadLetterService {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterService.class);

    private final JobQueue queue;
    private final JobRepository jobs;
    private final InferQueueProperties props;
    private final ApplicationEventPublisher events;

    public DeadLetterService(JobQueue queue, JobRepository jobs, InferQueueProperties props,
                             ApplicationEventPublisher events) {
        this.queue = queue;
        this.jobs = jobs;
        this.props = props;
        this.events = events;
    }

    public List<DeadLetterEntry> list(int limit) {
        return queue.deadLetters(Math.clamp(limit, 1, 500));
    }

    /**
     * Devuelve el job a la cola con los intentos y el TTL en cero. La entrada de
     * la DLQ se borra recién después del commit: si el proceso muere en el medio,
     * el peor caso es un job encolado que sigue figurando en la DLQ, y no un job
     * que desapareció de los dos lados.
     *
     * @return el job reencolado, o vacío si la entrada de la DLQ ya no existe.
     */
    @Transactional
    public Optional<Job> requeue(String recordId) {
        Optional<DeadLetterEntry> maybeEntry = queue.deadLetter(recordId);
        if (maybeEntry.isEmpty()) {
            return Optional.empty();
        }
        DeadLetterEntry entry = maybeEntry.get();

        Optional<Job> maybeJob = jobs.findByIdForUpdate(entry.jobId());
        if (maybeJob.isEmpty()) {
            // El job ya no está en la base: la entrada quedó huérfana, se limpia.
            queue.removeDeadLetter(recordId);
            log.warn("Entrada de DLQ {} apuntaba al job inexistente {}, la descarto", recordId, entry.jobId());
            return Optional.empty();
        }

        Job job = maybeJob.get();
        if (job.getStatus() == JobStatus.QUEUED || job.getStatus() == JobStatus.PROCESSING) {
            // Ya alguien lo reencoló; no queremos dos mensajes vivos del mismo job.
            queue.removeDeadLetter(recordId);
            return Optional.empty();
        }

        job.requeue(Instant.now().plus(props.queue().defaultTtl()));
        Job saved = jobs.save(job);
        afterCommit(() -> {
            queue.enqueue(QueueMessage.first(saved.getId(), saved.getPriority()));
            queue.removeDeadLetter(recordId);
            events.publishEvent(JobEvent.of(saved));
            log.info("Job {} reencolado desde la DLQ (motivo original: {})", saved.getId(), entry.reason());
        });
        return Optional.of(saved);
    }

    /** Descarta una entrada sin reencolar: el job muerto se acepta como muerto. */
    public boolean discard(String recordId) {
        return queue.removeDeadLetter(recordId);
    }

    public long purge() {
        long purged = queue.purgeDeadLetters();
        log.warn("DLQ vaciada: {} entradas descartadas", purged);
        return purged;
    }

    private void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
