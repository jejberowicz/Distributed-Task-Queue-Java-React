package com.inferqueue.api;

import com.inferqueue.domain.Job;
import com.inferqueue.domain.JobStatus;
import com.inferqueue.domain.JobType;
import com.inferqueue.domain.Priority;

import java.time.Instant;
import java.util.UUID;

public record JobResponse(
        UUID id,
        String model,
        JobType type,
        String prompt,
        Priority priority,
        JobStatus status,
        String result,
        String error,
        Integer tokensUsed,
        int retryCount,
        String claimedBy,
        Instant expiresAt,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt,
        Instant canceledAt
) {

    public static JobResponse from(Job job) {
        return new JobResponse(job.getId(), job.getModel(), job.getJobType(), job.getPrompt(), job.getPriority(),
                job.getStatus(), job.getResult(), job.getError(), job.getTokensUsed(), job.getRetryCount(),
                job.getClaimedBy(), job.getExpiresAt(), job.getCreatedAt(), job.getStartedAt(), job.getCompletedAt(),
                job.getCanceledAt());
    }
}
