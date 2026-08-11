package com.inferqueue.api;

import com.inferqueue.domain.Job;
import com.inferqueue.domain.JobEvent;
import com.inferqueue.domain.JobRepository;
import com.inferqueue.queue.JobQueue;
import com.inferqueue.queue.QueueMessage;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Alta de jobs en su propia transacción. Está separado de {@link JobService} a
 * propósito: la deduplicación por Idempotency-Key necesita ver el fallo del
 * índice único, y ese fallo recién aparece al commitear. Con el insert acá, la
 * llamada cruza el proxy transaccional y {@code JobService} puede capturar la
 * violación y resolverla releyendo el job que ganó la carrera.
 */
@Component
class JobWriter {

    private final JobRepository jobs;
    private final JobQueue queue;
    private final ApplicationEventPublisher events;

    JobWriter(JobRepository jobs, JobQueue queue, ApplicationEventPublisher events) {
        this.jobs = jobs;
        this.queue = queue;
        this.events = events;
    }

    @Transactional(readOnly = true)
    Optional<Job> findByIdempotencyKey(UUID apiKeyId, String idempotencyKey) {
        return jobs.findByApiKeyIdAndIdempotencyKey(apiKeyId, idempotencyKey);
    }

    @Transactional(readOnly = true)
    List<Job> findByIdempotencyKeys(UUID apiKeyId, Collection<String> keys) {
        return jobs.findByApiKeyIdAndIdempotencyKeyInOrderByIdempotencyKey(apiKeyId, keys);
    }

    /**
     * Persiste el job y recién después lo encola. El orden importa: si encoláramos
     * primero, un worker rápido podría buscar en la base un job que todavía no
     * está commiteado. Por eso el XADD se difiere al commit de la transacción.
     */
    @Transactional
    Job insert(Job job) {
        Job saved = jobs.save(job);
        afterCommit(() -> {
            queue.enqueue(QueueMessage.first(saved.getId(), saved.getPriority()));
            events.publishEvent(JobEvent.of(saved));
        });
        return saved;
    }

    /**
     * Alta en lote. Un solo commit para todo el batch: o entran todos los jobs o
     * no entra ninguno, y el cliente no queda con un lote a medio encolar que no
     * puede reintentar sin duplicar.
     */
    @Transactional
    List<Job> insertAll(List<Job> batch) {
        List<Job> saved = jobs.saveAll(batch);
        afterCommit(() -> saved.forEach(job -> {
            queue.enqueue(QueueMessage.first(job.getId(), job.getPriority()));
            events.publishEvent(JobEvent.of(job));
        }));
        return saved;
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
