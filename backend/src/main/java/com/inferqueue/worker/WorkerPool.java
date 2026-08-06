package com.inferqueue.worker;

import com.inferqueue.config.InferQueueProperties;
import com.inferqueue.queue.JobQueue;
import com.inferqueue.queue.QueueMessage;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Pool de consumers del stream. Cada worker es un virtual thread con su propio
 * nombre de consumer dentro del group, así el PEL queda particionado por worker
 * y se puede identificar exactamente qué mensajes quedaron huérfanos si uno muere.
 */
@Component
@ConditionalOnProperty(name = "inferqueue.worker.enabled", havingValue = "true", matchIfMissing = true)
public class WorkerPool {

    private static final Logger log = LoggerFactory.getLogger(WorkerPool.class);
    private static final int BATCH_SIZE = 1;

    private final JobQueue queue;
    private final JobExecutor executor;
    private final InferQueueProperties props;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private ExecutorService threads;
    private CountDownLatch stopped;
    private final String instanceId = shortId();

    public WorkerPool(JobQueue queue, JobExecutor executor, InferQueueProperties props) {
        this.queue = queue;
        this.executor = executor;
        this.props = props;
    }

    @PostConstruct
    public void start() {
        queue.ensureConsumerGroups();
        int concurrency = props.worker().concurrency();
        running.set(true);
        stopped = new CountDownLatch(concurrency);
        threads = Executors.newVirtualThreadPerTaskExecutor();
        for (int i = 0; i < concurrency; i++) {
            String workerId = "%s-%d".formatted(instanceId, i);
            threads.submit(() -> runLoop(workerId));
        }
        log.info("WorkerPool arrancado: {} workers (instancia {})", concurrency, instanceId);
    }

    private void runLoop(String workerId) {
        log.info("Worker {} escuchando", workerId);
        try {
            while (running.get()) {
                try {
                    List<MapRecord<String, String, String>> records = queue.read(workerId, BATCH_SIZE);
                    for (MapRecord<String, String, String> record : records) {
                        if (!running.get()) {
                            // Shutdown en curso: no lo procesamos ni lo ackeamos.
                            // Queda en el PEL y otro worker lo reclamará.
                            break;
                        }
                        dispatch(record, workerId);
                    }
                } catch (RuntimeException e) {
                    if (running.get()) {
                        log.error("Worker {} falló leyendo la cola, reintento en 1s", workerId, e);
                        sleepQuietly();
                    }
                }
            }
        } finally {
            log.info("Worker {} detenido", workerId);
            stopped.countDown();
        }
    }

    private void dispatch(MapRecord<String, String, String> record, String workerId) {
        QueueMessage message;
        try {
            message = QueueMessage.fromMap(record.getValue());
        } catch (RuntimeException e) {
            log.error("Mensaje ilegible {} en {}, lo ackeo para no bloquear la cola", record.getId(),
                    record.getStream(), e);
            queue.ack(record.getStream(), record.getId());
            queue.delete(record.getStream(), record.getId());
            return;
        }
        executor.process(record.getStream(), record.getId(), message, workerId);
    }

    private void sleepQuietly() {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Shutdown ordenado: dejamos de tomar mensajes nuevos y esperamos a que
     * terminen los que están en vuelo. Lo que no llegue a ackearse queda en el
     * PEL y lo recupera el reclaimer en otra instancia.
     */
    @PreDestroy
    public void stop() throws InterruptedException {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        log.info("Deteniendo WorkerPool {}...", instanceId);
        threads.shutdown();
        boolean drained = stopped.await(30, TimeUnit.SECONDS);
        if (!drained) {
            log.warn("Algunos workers no terminaron a tiempo; sus mensajes quedan en el PEL");
            threads.shutdownNow();
        }
    }

    public String instanceId() {
        return instanceId;
    }

    private static String shortId() {
        return java.util.UUID.randomUUID().toString().substring(0, 8);
    }
}
