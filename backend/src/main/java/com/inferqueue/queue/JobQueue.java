package com.inferqueue.queue;

import com.inferqueue.config.InferQueueProperties;
import com.inferqueue.domain.Priority;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.RedisStreamCommands.XClaimOptions;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Wrapper sobre Redis Streams. Dos streams (prioridad y standard) comparten un
 * consumer group; los mensajes quedan en el PEL del consumer hasta que se hace
 * XACK, que es lo que nos da at-least-once delivery si un worker muere.
 */
@Component
public class JobQueue {

    private static final Logger log = LoggerFactory.getLogger(JobQueue.class);

    private final StringRedisTemplate redis;
    private final InferQueueProperties props;

    public JobQueue(StringRedisTemplate redis, InferQueueProperties props) {
        this.redis = redis;
        this.props = props;
    }

    private StreamOperations<String, String, String> ops() {
        return redis.opsForStream();
    }

    public String streamFor(Priority priority) {
        return priority == Priority.PRIORITY
                ? props.queue().streams().priority()
                : props.queue().streams().standard();
    }

    /** Crea los consumer groups si no existen (idempotente, se llama al arrancar). */
    public void ensureConsumerGroups() {
        for (String stream : List.of(streamFor(Priority.PRIORITY), streamFor(Priority.STANDARD))) {
            createGroup(stream);
        }
    }

    private void createGroup(String stream) {
        try {
            // MKSTREAM: crea el stream vacío si todavía no existe.
            ops().createGroup(stream, ReadOffset.from("0"), props.queue().consumerGroup());
            log.info("Consumer group '{}' creado sobre el stream '{}'", props.queue().consumerGroup(), stream);
        } catch (RedisSystemException | InvalidDataAccessApiUsageException e) {
            // BUSYGROUP: ya existía. Cualquier otra cosa sí es un problema.
            if (e.getCause() != null && String.valueOf(e.getCause().getMessage()).contains("BUSYGROUP")) {
                log.debug("Consumer group ya existente sobre '{}'", stream);
            } else {
                throw e;
            }
        }
    }

    public RecordId enqueue(QueueMessage message) {
        String stream = streamFor(message.priority());
        RecordId id = ops().add(StreamRecords.mapBacked(message.toMap()).withStreamKey(stream));
        log.debug("Job {} encolado en {} (attempt={}) con id {}", message.jobId(), stream, message.attempt(), id);
        return id;
    }

    /**
     * Lee mensajes nuevos. Primero mira el stream de prioridad sin bloquear; sólo
     * si está vacío se bloquea sobre el standard. Así un job premium nunca espera
     * detrás de uno free, a costa de un round-trip extra por poll.
     */
    public List<MapRecord<String, String, String>> read(String consumerName, int count) {
        Consumer consumer = Consumer.from(props.queue().consumerGroup(), consumerName);

        List<MapRecord<String, String, String>> priorityBatch = ops().read(consumer,
                StreamReadOptions.empty().count(count),
                StreamOffset.create(streamFor(Priority.PRIORITY), ReadOffset.lastConsumed()));
        if (priorityBatch != null && !priorityBatch.isEmpty()) {
            return priorityBatch;
        }

        List<MapRecord<String, String, String>> standardBatch = ops().read(consumer,
                StreamReadOptions.empty().count(count).block(props.worker().pollTimeout()),
                StreamOffset.create(streamFor(Priority.STANDARD), ReadOffset.lastConsumed()));
        return standardBatch == null ? List.of() : standardBatch;
    }

    public void ack(String stream, RecordId recordId) {
        ops().acknowledge(stream, props.queue().consumerGroup(), recordId);
    }

    /** Saca el mensaje del stream una vez procesado y ackeado, para que no crezca sin límite. */
    public void delete(String stream, RecordId recordId) {
        ops().delete(stream, recordId);
    }

    /**
     * Busca mensajes huérfanos: entregados a un consumer que nunca hizo XACK y
     * llevan más de claim-idle-timeout sin tocar. Se los reasigna a este worker
     * con XCLAIM.
     */
    public List<MapRecord<String, String, String>> claimStale(String stream, String consumerName) {
        Duration idle = props.queue().claimIdleTimeout();
        PendingMessages pending;
        try {
            pending = ops().pending(stream, props.queue().consumerGroup(),
                    Range.unbounded(), props.queue().reclaimBatchSize());
        } catch (RuntimeException e) {
            log.warn("No se pudo leer el PEL de {}: {}", stream, e.toString());
            return List.of();
        }
        if (pending == null || pending.isEmpty()) {
            return List.of();
        }

        List<RecordId> stale = new ArrayList<>();
        for (PendingMessage message : pending) {
            if (message.getElapsedTimeSinceLastDelivery().compareTo(idle) >= 0) {
                stale.add(message.getId());
            }
        }
        if (stale.isEmpty()) {
            return List.of();
        }

        List<MapRecord<String, String, String>> claimed = ops().claim(stream, props.queue().consumerGroup(),
                consumerName, XClaimOptions.minIdle(idle).ids(stale.toArray(RecordId[]::new)));
        if (claimed != null && !claimed.isEmpty()) {
            log.warn("Reclamados {} mensajes huérfanos de {} para el consumer {}", claimed.size(), stream, consumerName);
        }
        return claimed == null ? List.of() : claimed;
    }

    /** Manda el job a la dead-letter queue conservando el motivo del descarte. */
    public void toDeadLetter(QueueMessage message, String reason) {
        Map<String, String> payload = new java.util.HashMap<>(message.toMap());
        payload.put("reason", reason == null ? "unknown" : reason);
        payload.put("deadAt", Long.toString(System.currentTimeMillis()));
        ops().add(StreamRecords.mapBacked(payload).withStreamKey(props.queue().dlqStream()));
        log.error("Job {} enviado a la DLQ tras {} intentos: {}", message.jobId(), message.attempt(), reason);
    }

    /** Contenido de la DLQ, del más viejo al más nuevo. Para inspección desde admin. */
    public List<DeadLetterEntry> deadLetters(int limit) {
        List<MapRecord<String, String, String>> records =
                ops().range(props.queue().dlqStream(), Range.unbounded(), Limit.limit().count(limit));
        return records == null ? List.of() : records.stream().map(DeadLetterEntry::from).toList();
    }

    public Optional<DeadLetterEntry> deadLetter(String recordId) {
        List<MapRecord<String, String, String>> records =
                ops().range(props.queue().dlqStream(), Range.closed(recordId, recordId));
        return records == null || records.isEmpty()
                ? Optional.empty()
                : Optional.of(DeadLetterEntry.from(records.get(0)));
    }

    public boolean removeDeadLetter(String recordId) {
        Long removed = ops().delete(props.queue().dlqStream(), RecordId.of(recordId));
        return removed != null && removed > 0;
    }

    /** Vacía la DLQ. Devuelve cuántas entradas se descartaron. */
    public long purgeDeadLetters() {
        Long size = ops().size(props.queue().dlqStream());
        redis.delete(props.queue().dlqStream());
        return size == null ? 0 : size;
    }

    /**
     * Recorta los streams a un máximo aproximado de entradas. Los mensajes se
     * borran al ackearse, pero un ack perdido o un XADD sin consumir dejan
     * residuo; sin esto el stream crece para siempre.
     *
     * <p>El límite es aproximado a propósito (~): XTRIM exacto obliga a Redis a
     * recorrer nodos parciales del radix tree, y acá no necesitamos precisión.
     */
    public long trim(String stream, long maxLength) {
        Long remaining = ops().trim(stream, maxLength, true);
        return remaining == null ? 0 : remaining;
    }

    public long depth(Priority priority) {
        Long size = ops().size(streamFor(priority));
        return size == null ? 0 : size;
    }

    public long deadLetterDepth() {
        Long size = ops().size(props.queue().dlqStream());
        return size == null ? 0 : size;
    }

    public long pendingCount(Priority priority) {
        try {
            PendingMessages pending = ops().pending(streamFor(priority), props.queue().consumerGroup(),
                    Range.unbounded(), 1000);
            return pending == null ? 0 : pending.size();
        } catch (RuntimeException e) {
            return 0;
        }
    }
}
