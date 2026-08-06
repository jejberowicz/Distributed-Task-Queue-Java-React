package com.inferqueue.worker;

import com.inferqueue.domain.Priority;
import com.inferqueue.metrics.QueueMetrics;
import com.inferqueue.queue.JobQueue;
import com.inferqueue.queue.QueueMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Recovery de workers muertos. Recorre el PEL de cada stream buscando mensajes
 * entregados hace más de claim-idle-timeout sin XACK y los reclama con XCLAIM
 * para reprocesarlos acá.
 *
 * <p>Race condition conocido: si el timeout es más corto que la inference más
 * lenta, un worker vivo pero ocupado puede perder su mensaje y quedar procesando
 * un job que otro ya tomó. Se mitiga con dos cosas: el timeout se configura por
 * encima del p99 de la inference, y {@link JobExecutor} ignora las reentregas de
 * jobs que ya están en estado terminal, así el peor caso es trabajo duplicado y
 * no un resultado corrupto.
 */
@Component
@ConditionalOnProperty(name = "inferqueue.worker.enabled", havingValue = "true", matchIfMissing = true)
public class PendingReclaimer {

    private static final Logger log = LoggerFactory.getLogger(PendingReclaimer.class);

    private final JobQueue queue;
    private final JobExecutor executor;
    private final WorkerPool pool;
    private final QueueMetrics metrics;

    public PendingReclaimer(JobQueue queue, JobExecutor executor, WorkerPool pool, QueueMetrics metrics) {
        this.queue = queue;
        this.executor = executor;
        this.pool = pool;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${inferqueue.queue.reclaim-interval}")
    public void reclaim() {
        String consumerName = pool.instanceId() + "-reclaimer";
        for (Priority priority : Priority.values()) {
            String stream = queue.streamFor(priority);
            try {
                List<MapRecord<String, String, String>> claimed = queue.claimStale(stream, consumerName);
                if (claimed.isEmpty()) {
                    continue;
                }
                metrics.recordReclaimed(claimed.size());
                for (MapRecord<String, String, String> record : claimed) {
                    executor.process(stream, record.getId(), QueueMessage.fromMap(record.getValue()), consumerName);
                }
            } catch (RuntimeException e) {
                log.error("Fallo reclamando mensajes de {}", stream, e);
            }
        }
    }
}
