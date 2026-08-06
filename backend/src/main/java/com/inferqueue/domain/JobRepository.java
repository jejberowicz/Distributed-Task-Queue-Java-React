package com.inferqueue.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface JobRepository extends JpaRepository<Job, UUID> {

    Page<Job> findByApiKeyIdOrderByCreatedAtDesc(UUID apiKeyId, Pageable pageable);

    Page<Job> findByApiKeyIdAndStatusOrderByCreatedAtDesc(UUID apiKeyId, JobStatus status, Pageable pageable);

    @Query("select j from Job j where j.status in ('QUEUED', 'PROCESSING') and j.expiresAt < :now")
    List<Job> findExpired(@Param("now") Instant now);

    long countByStatus(JobStatus status);

    @Query("select coalesce(sum(j.tokensUsed), 0) from Job j where j.apiKeyId = :apiKeyId and j.createdAt >= :since")
    long sumTokensSince(@Param("apiKeyId") UUID apiKeyId, @Param("since") Instant since);
}
