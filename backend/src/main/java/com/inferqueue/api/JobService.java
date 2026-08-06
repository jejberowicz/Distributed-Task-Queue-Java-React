package com.inferqueue.api;

import com.inferqueue.config.InferQueueProperties;
import com.inferqueue.domain.ApiKey;
import com.inferqueue.domain.Job;
import com.inferqueue.domain.JobEvent;
import com.inferqueue.domain.JobRepository;
import com.inferqueue.domain.JobStatus;
import com.inferqueue.domain.JobType;
import com.inferqueue.domain.Priority;
import com.inferqueue.queue.JobQueue;
import com.inferqueue.queue.QueueMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class JobService {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);

    private final JobRepository jobs;
    private final JobQueue queue;
    private final InferQueueProperties props;
    private final ApplicationEventPublisher events;

    public JobService(JobRepository jobs, JobQueue queue, InferQueueProperties props,
                      ApplicationEventPublisher events) {
        this.jobs = jobs;
        this.queue = queue;
        this.props = props;
        this.events = events;
    }

    /**
     * Persiste el job y recién después lo encola. El orden importa: si encoláramos
     * primero, un worker rápido podría buscar en la base un job que todavía no
     * está commiteado. Por eso el XADD se difiere al commit de la transacción.
     */
    @Transactional
    public Job submit(ApiKey apiKey, SubmitJobRequest request) {
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
        Job saved = jobs.save(job);

        afterCommit(() -> {
            queue.enqueue(QueueMessage.first(saved.getId(), priority));
            events.publishEvent(JobEvent.of(saved));
        });
        log.info("Job {} aceptado (model={}, priority={}, ttl={}s)", saved.getId(), saved.getModel(), priority,
                ttl.toSeconds());
        return saved;
    }

    @Transactional(readOnly = true)
    public Optional<Job> find(UUID jobId) {
        return jobs.findById(jobId);
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
    public List<Long> statusCounts() {
        return List.of(
                jobs.countByStatus(JobStatus.QUEUED),
                jobs.countByStatus(JobStatus.PROCESSING),
                jobs.countByStatus(JobStatus.DONE),
                jobs.countByStatus(JobStatus.FAILED),
                jobs.countByStatus(JobStatus.DEAD),
                jobs.countByStatus(JobStatus.EXPIRED));
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
