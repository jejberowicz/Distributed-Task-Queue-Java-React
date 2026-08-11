package com.inferqueue.api;

import com.inferqueue.config.InferQueueProperties;
import com.inferqueue.domain.ApiKey;
import com.inferqueue.domain.Job;
import com.inferqueue.domain.JobEvent;
import com.inferqueue.domain.JobRepository;
import com.inferqueue.domain.JobStatus;
import com.inferqueue.domain.JobType;
import com.inferqueue.domain.Priority;
import com.inferqueue.queue.DelayedQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class JobService {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);

    private final JobRepository jobs;
    private final JobWriter writer;
    private final DelayedQueue delayedQueue;
    private final InferQueueProperties props;
    private final ApplicationEventPublisher events;

    public JobService(JobRepository jobs, JobWriter writer, DelayedQueue delayedQueue, InferQueueProperties props,
                      ApplicationEventPublisher events) {
        this.jobs = jobs;
        this.writer = writer;
        this.delayedQueue = delayedQueue;
        this.props = props;
        this.events = events;
    }

    /**
     * Encola un job. Con un Idempotency-Key, un reintento del cliente (timeout de
     * red, retry automático de su SDK) devuelve el job original en vez de encolar
     * uno nuevo: la deduplicación se apoya en el índice único (api_key_id, key),
     * no en un chequeo previo, así dos requests simultáneos tampoco duplican.
     */
    public Submission submit(ApiKey apiKey, SubmitJobRequest request, String idempotencyKey) {
        if (idempotencyKey != null) {
            Optional<Job> existing = writer.findByIdempotencyKey(apiKey.getId(), idempotencyKey);
            if (existing.isPresent()) {
                log.info("Idempotency-Key '{}' ya conocido: devuelvo el job {}", idempotencyKey,
                        existing.get().getId());
                return new Submission(existing.get(), true);
            }
        }

        try {
            Job saved = writer.insert(build(apiKey, request, idempotencyKey));
            log.info("Job {} aceptado (model={}, priority={})", saved.getId(), saved.getModel(),
                    saved.getPriority());
            return new Submission(saved, false);
        } catch (DataIntegrityViolationException e) {
            // Otro request con el mismo Idempotency-Key ganó la carrera por el
            // índice único. El suyo es el job válido; el nuestro nunca existió.
            if (idempotencyKey == null) {
                throw e;
            }
            return writer.findByIdempotencyKey(apiKey.getId(), idempotencyKey)
                    .map(job -> new Submission(job, true))
                    .orElseThrow(() -> e);
        }
    }

    /**
     * Encola un lote en una sola transacción: o entran todos los jobs o no entra
     * ninguno. Eso hace que reintentar un batch que falló sea seguro sin más
     * ceremonia — si falló, no quedó nada encolado.
     *
     * <p>Para el caso en que el batch sí entró pero se perdió la respuesta, el
     * Idempotency-Key del lote se deriva por ítem ({@code clave#indice}), así la
     * deduplicación reusa el mismo índice único que el submit individual.
     */
    public BatchSubmission submitBatch(ApiKey apiKey, BatchSubmitRequest request, String idempotencyKey) {
        List<String> derivedKeys = derivedKeys(idempotencyKey, request.jobs().size());

        if (idempotencyKey != null) {
            List<Job> existing = writer.findByIdempotencyKeys(apiKey.getId(), derivedKeys);
            if (!existing.isEmpty()) {
                log.info("Batch con Idempotency-Key '{}' ya conocido: devuelvo {} jobs", idempotencyKey,
                        existing.size());
                return new BatchSubmission(existing, true);
            }
        }

        List<Job> batch = new ArrayList<>(request.jobs().size());
        for (int i = 0; i < request.jobs().size(); i++) {
            batch.add(build(apiKey, request.jobs().get(i), derivedKeys == null ? null : derivedKeys.get(i)));
        }

        try {
            List<Job> saved = writer.insertAll(batch);
            log.info("Batch de {} jobs aceptado para la key {}", saved.size(), apiKey.getId());
            return new BatchSubmission(saved, false);
        } catch (DataIntegrityViolationException e) {
            if (idempotencyKey == null) {
                throw e;
            }
            List<Job> winner = writer.findByIdempotencyKeys(apiKey.getId(), derivedKeys);
            if (winner.isEmpty()) {
                throw e;
            }
            return new BatchSubmission(winner, true);
        }
    }

    /** Un key por ítem derivado del key del lote, para reusar el índice único por job. */
    private List<String> derivedKeys(String idempotencyKey, int size) {
        if (idempotencyKey == null) {
            return null;
        }
        List<String> keys = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            keys.add(idempotencyKey + "#" + i);
        }
        return keys;
    }

    private Job build(ApiKey apiKey, SubmitJobRequest request, String idempotencyKey) {
        Priority priority = request.priority() != null ? request.priority() : apiKey.getTier().defaultPriority();
        Duration ttl = request.ttlSeconds() != null
                ? Duration.ofSeconds(request.ttlSeconds())
                : props.queue().defaultTtl();

        Job job = new Job(
                apiKey.getId(),
                request.model(),
                request.type() != null ? request.type() : JobType.COMPLETION,
                request.prompt(),
                priority,
                Instant.now().plus(ttl));
        job.setIdempotencyKey(idempotencyKey);
        return job;
    }

    /** {@code replayed} distingue un job recién creado de uno devuelto por idempotencia. */
    public record Submission(Job job, boolean replayed) {
    }

    public record BatchSubmission(List<Job> jobs, boolean replayed) {
    }

    @Transactional(readOnly = true)
    public Optional<Job> find(UUID jobId) {
        return jobs.findById(jobId);
    }

    /**
     * Cancela un job propio. No hay forma de sacar un mensaje ya escrito en un
     * stream de Redis, así que la cancelación es un estado en Postgres: el
     * mensaje sigue su curso y el worker lo descarta al ver el estado terminal.
     * Lo que sí se limpia es el retry diferido, que todavía no llegó al stream.
     *
     * @return el job cancelado, o vacío si ya estaba en estado terminal.
     */
    @Transactional
    public Optional<Job> cancel(UUID jobId) {
        Optional<Job> canceled = jobs.findByIdForUpdate(jobId)
                .filter(job -> !job.getStatus().isTerminal())
                .map(job -> {
                    job.markCanceled();
                    return jobs.save(job);
                });
        canceled.ifPresent(job -> afterCommit(() -> {
            delayedQueue.cancel(job.getId());
            events.publishEvent(JobEvent.of(job));
        }));
        return canceled;
    }

    @Transactional(readOnly = true)
    public Page<Job> list(UUID apiKeyId, JobStatus status, int page, int size) {
        PageRequest pageable = PageRequest.of(page, Math.min(size, 200));
        return status == null
                ? jobs.findByApiKeyIdOrderByCreatedAtDesc(apiKeyId, pageable)
                : jobs.findByApiKeyIdAndStatusOrderByCreatedAtDesc(apiKeyId, status, pageable);
    }

    @Transactional(readOnly = true)
    public long tokensUsedSince(UUID apiKeyId, Instant since) {
        return jobs.sumTokensSince(apiKeyId, since);
    }

    @Transactional(readOnly = true)
    public Map<JobStatus, Long> statusCounts() {
        Map<JobStatus, Long> counts = new EnumMap<>(JobStatus.class);
        for (JobStatus status : JobStatus.values()) {
            counts.put(status, jobs.countByStatus(status));
        }
        return counts;
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
