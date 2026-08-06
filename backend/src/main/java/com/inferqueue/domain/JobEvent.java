package com.inferqueue.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Snapshot inmutable de un job para publicar hacia el dashboard. Se emite como
 * ApplicationEvent para que la capa de WebSocket no dependa del worker ni de la API.
 */
public record JobEvent(
        UUID jobId,
        UUID apiKeyId,
        String model,
        JobType jobType,
        Priority priority,
        JobStatus status,
        String result,
        String error,
        Integer tokensUsed,
        int retryCount,
        String claimedBy,
        Instant createdAt,
        Instant completedAt
) {

    public static JobEvent of(Job job) {
        return new JobEvent(
                job.getId(),
                job.getApiKeyId(),
                job.getModel(),
                job.getJobType(),
                job.getPriority(),
                job.getStatus(),
                job.getResult(),
                job.getError(),
                job.getTokensUsed(),
                job.getRetryCount(),
                job.getClaimedBy(),
                job.getCreatedAt(),
                job.getCompletedAt());
    }
}
