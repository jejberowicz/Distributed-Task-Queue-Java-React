package com.inferqueue.queue;

import com.inferqueue.domain.Priority;

import java.util.Map;
import java.util.UUID;

/**
 * Payload que viaja por el stream. Deliberadamente chico: sólo el id del job y
 * metadata de routing. El estado completo vive en PostgreSQL, así el stream no
 * es la fuente de verdad y podemos reencolar sin arrastrar prompts grandes.
 */
public record QueueMessage(UUID jobId, Priority priority, int attempt, long enqueuedAtEpochMillis) {

    public static QueueMessage first(UUID jobId, Priority priority) {
        return new QueueMessage(jobId, priority, 0, System.currentTimeMillis());
    }

    public QueueMessage nextAttempt() {
        return new QueueMessage(jobId, priority, attempt + 1, System.currentTimeMillis());
    }

    public Map<String, String> toMap() {
        return Map.of(
                "jobId", jobId.toString(),
                "priority", priority.name(),
                "attempt", Integer.toString(attempt),
                "enqueuedAt", Long.toString(enqueuedAtEpochMillis)
        );
    }

    public static QueueMessage fromMap(Map<String, String> map) {
        return new QueueMessage(
                UUID.fromString(map.get("jobId")),
                Priority.valueOf(map.getOrDefault("priority", Priority.STANDARD.name())),
                Integer.parseInt(map.getOrDefault("attempt", "0")),
                Long.parseLong(map.getOrDefault("enqueuedAt", "0"))
        );
    }
}
