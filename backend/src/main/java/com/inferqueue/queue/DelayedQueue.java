package com.inferqueue.queue;

import com.inferqueue.config.InferQueueProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Redis Streams no tiene entrega diferida, así que el backoff exponencial se
 * implementa con un sorted set: el job se guarda con score = timestamp en el que
 * vuelve a estar listo, y un scheduler promueve al stream lo que ya venció.
 */
@Component
public class DelayedQueue {

    private static final Logger log = LoggerFactory.getLogger(DelayedQueue.class);
    private static final String ZSET_KEY = "infer:delayed";
    private static final int PROMOTE_BATCH = 100;

    private final StringRedisTemplate redis;
    private final JobQueue jobQueue;
    private final InferQueueProperties props;

    public DelayedQueue(StringRedisTemplate redis, JobQueue jobQueue, InferQueueProperties props) {
        this.redis = redis;
        this.jobQueue = jobQueue;
        this.props = props;
    }

    /** Backoff exponencial: base * 2^attempt, con techo configurable. */
    public Duration backoffFor(int attempt) {
        Duration base = props.queue().retryBackoffBase();
        Duration max = props.queue().retryBackoffMax();
        // El shift se satura rápido; cortamos en 16x para no desbordar el long.
        long multiplier = 1L << Math.min(attempt, 16);
        Duration delay = base.multipliedBy(multiplier);
        return delay.compareTo(max) > 0 ? max : delay;
    }

    public void scheduleRetry(QueueMessage message) {
        Duration delay = backoffFor(message.attempt());
        long readyAt = Instant.now().plus(delay).toEpochMilli();
        redis.opsForZSet().add(ZSET_KEY, encode(message), readyAt);
        log.info("Job {} reprogramado (attempt={}) en {}s", message.jobId(), message.attempt(), delay.toSeconds());
    }

    @Scheduled(fixedDelayString = "PT1S")
    public void promoteDue() {
        long now = Instant.now().toEpochMilli();
        Set<ZSetOperations.TypedTuple<String>> due =
                redis.opsForZSet().rangeByScoreWithScores(ZSET_KEY, 0, now, 0, PROMOTE_BATCH);
        if (due == null || due.isEmpty()) {
            return;
        }
        for (ZSetOperations.TypedTuple<String> tuple : due) {
            String member = tuple.getValue();
            if (member == null) {
                continue;
            }
            // Sólo promueve el que gana el remove: evita doble encolado si hay
            // varias instancias del gateway corriendo el mismo scheduler.
            Long removed = redis.opsForZSet().remove(ZSET_KEY, member);
            if (removed != null && removed > 0) {
                jobQueue.enqueue(decode(member));
            }
        }
    }

    /**
     * Saca del ZSET los retries pendientes de un job. Se usa al cancelar: sin
     * esto el job cancelado igual volvería al stream cuando venza su backoff, y
     * aunque el executor lo descartaría por estado terminal, es trabajo al pedo.
     */
    public long cancel(UUID jobId) {
        long removed = 0;
        try (Cursor<ZSetOperations.TypedTuple<String>> cursor = redis.opsForZSet()
                .scan(ZSET_KEY, ScanOptions.scanOptions().match(jobId + "|*").count(100).build())) {
            while (cursor.hasNext()) {
                String member = cursor.next().getValue();
                Long dropped = member == null ? null : redis.opsForZSet().remove(ZSET_KEY, member);
                removed += dropped == null ? 0 : dropped;
            }
        }
        if (removed > 0) {
            log.info("Job {}: {} retries diferidos descartados por cancelación", jobId, removed);
        }
        return removed;
    }

    public long size() {
        Long size = redis.opsForZSet().size(ZSET_KEY);
        return size == null ? 0 : size;
    }

    private String encode(QueueMessage message) {
        return "%s|%s|%d|%d".formatted(message.jobId(), message.priority(), message.attempt(),
                message.enqueuedAtEpochMillis());
    }

    private QueueMessage decode(String member) {
        String[] parts = member.split("\\|");
        return new QueueMessage(
                java.util.UUID.fromString(parts[0]),
                com.inferqueue.domain.Priority.valueOf(parts[1]),
                Integer.parseInt(parts[2]),
                Long.parseLong(parts[3]));
    }
}
