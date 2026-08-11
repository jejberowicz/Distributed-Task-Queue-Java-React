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
import java.util.EnumMap;
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
