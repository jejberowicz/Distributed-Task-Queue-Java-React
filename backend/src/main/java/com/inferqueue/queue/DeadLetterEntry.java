package com.inferqueue.queue;

import com.inferqueue.domain.Priority;
import org.springframework.data.redis.connection.stream.MapRecord;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Una entrada de la dead-letter queue. Conserva el motivo del descarte y cuántos
 * intentos se hicieron: sin eso la DLQ es un cementerio sin lápidas y no sirve
 * para decidir si el job vale la pena reintentarlo.
 */
public record DeadLetterEntry(
        String recordId,
        UUID jobId,
        Priority priority,
        int attempt,
        String reason,
        Instant deadAt
) {

    static DeadLetterEntry from(MapRecord<String, String, String> record) {
        Map<String, String> fields = record.getValue();
        return new DeadLetterEntry(
                record.getId().getValue(),
                UUID.fromString(fields.get("jobId")),
                Priority.valueOf(fields.getOrDefault("priority", Priority.STANDARD.name())),
                Integer.parseInt(fields.getOrDefault("attempt", "0")),
                fields.getOrDefault("reason", "unknown"),
                Instant.ofEpochMilli(Long.parseLong(fields.getOrDefault("deadAt", "0"))));
    }
}
