package com.inferqueue.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "jobs")
public class Job {

    @Id
    private UUID id;

    @Column(name = "api_key_id", nullable = false)
    private UUID apiKeyId;

    @Column(nullable = false)
    private String model;

    @Enumerated(EnumType.STRING)
    @Column(name = "job_type", nullable = false)
    private JobType jobType;

    @Column(nullable = false)
    private String prompt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Priority priority;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JobStatus status;

    @Column
    private String result;

    @Column
    private String error;

    @Column(name = "tokens_used")
    private Integer tokensUsed;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "claimed_by")
    private String claimedBy;

    @Column(name = "stream_msg_id")
    private String streamMsgId;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "canceled_at")
    private Instant canceledAt;

    /** Header Idempotency-Key del submit, si el cliente mandó uno. */
    @Column(name = "idempotency_key")
    private String idempotencyKey;

    protected Job() {
    }

    public Job(UUID apiKeyId, String model, JobType jobType, String prompt, Priority priority, Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.apiKeyId = apiKeyId;
        this.model = model;
        this.jobType = jobType;
        this.prompt = prompt;
        this.priority = priority;
        this.status = JobStatus.QUEUED;
        this.retryCount = 0;
        this.expiresAt = expiresAt;
        this.createdAt = Instant.now();
    }

    public void markProcessing(String workerId) {
        this.status = JobStatus.PROCESSING;
        this.claimedBy = workerId;
        this.startedAt = Instant.now();
    }

    public void markDone(String result, Integer tokensUsed) {
        this.status = JobStatus.DONE;
        this.result = result;
        this.tokensUsed = tokensUsed;
        this.error = null;
        this.completedAt = Instant.now();
    }

    /** Falló pero se va a reintentar: incrementa el contador y vuelve a QUEUED. */
    public void markRetrying(String error) {
        this.status = JobStatus.FAILED;
        this.error = error;
        this.retryCount++;
    }

    public void markDead(String error) {
        this.status = JobStatus.DEAD;
        this.error = error;
        this.completedAt = Instant.now();
    }

    public void markExpired() {
        this.status = JobStatus.EXPIRED;
        this.error = "Job excedió su TTL antes de completarse";
        this.completedAt = Instant.now();
    }

    /**
     * Baja a pedido del cliente. Sólo tiene efecto si el job todavía no terminó:
     * un job que ya se completó no se "descompleta", y uno que está PROCESSING
     * queda CANCELED igual — el worker descarta su resultado al ver el estado terminal.
     */
    public void markCanceled() {
        this.status = JobStatus.CANCELED;
        this.error = "Cancelado por el cliente";
        this.canceledAt = Instant.now();
        this.completedAt = this.canceledAt;
    }

    /**
     * Vuelve a poner en cola un job que había muerto. Es la única transición que
     * saca a un job de un estado terminal, y es deliberada: la hace un operador
     * desde la DLQ, no el sistema solo. El contador de reintentos se reinicia
     * (si no, el job volvería a morir en el primer fallo) y el TTL se renueva,
     * porque el original ya venció hace rato.
     */
    public void requeue(Instant expiresAt) {
        this.status = JobStatus.QUEUED;
        this.error = null;
        this.result = null;
        this.retryCount = 0;
        this.claimedBy = null;
        this.streamMsgId = null;
        this.startedAt = null;
        this.completedAt = null;
        this.expiresAt = expiresAt;
    }

    public boolean isExpired(Instant now) {
        return now.isAfter(expiresAt);
    }

    public UUID getId() {
        return id;
    }

    public UUID getApiKeyId() {
        return apiKeyId;
    }

    public String getModel() {
        return model;
    }

    public JobType getJobType() {
        return jobType;
    }

    public String getPrompt() {
        return prompt;
    }

    public Priority getPriority() {
        return priority;
    }

    public JobStatus getStatus() {
        return status;
    }

    public String getResult() {
        return result;
    }

    public String getError() {
        return error;
    }

    public Integer getTokensUsed() {
        return tokensUsed;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public String getClaimedBy() {
        return claimedBy;
    }

    public String getStreamMsgId() {
        return streamMsgId;
    }

    public void setStreamMsgId(String streamMsgId) {
        this.streamMsgId = streamMsgId;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Instant getCanceledAt() {
        return canceledAt;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }
}
