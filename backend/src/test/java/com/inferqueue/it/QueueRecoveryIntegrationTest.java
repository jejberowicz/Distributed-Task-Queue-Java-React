package com.inferqueue.it;

import com.inferqueue.api.DeadLetterService;
import com.inferqueue.api.JobService;
import com.inferqueue.api.SubmitJobRequest;
import com.inferqueue.domain.ApiKey;
import com.inferqueue.domain.Job;
import com.inferqueue.domain.JobRepository;
import com.inferqueue.domain.JobStatus;
import com.inferqueue.domain.JobType;
import com.inferqueue.domain.Priority;
import com.inferqueue.domain.Tier;
import com.inferqueue.queue.DelayedQueue;
import com.inferqueue.queue.JobQueue;
import com.inferqueue.queue.QueueMessage;
import com.inferqueue.security.ApiKeyService;
import com.inferqueue.worker.JobExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.stream.MapRecord;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Recuperación ante fallas, con el PEL de Redis de verdad.
 *
 * <p>El pool de workers está apagado a propósito: acá cada paso se dispara a
 * mano para poder detener el flujo justo donde importa — un mensaje entregado y
 * nunca ackeado, que es exactamente lo que deja un worker que muere a mitad de
 * un job. Con el pool andando, un worker vivo tomaría el mensaje antes de que el
 * test pueda mirarlo.
 */
@SpringBootTest(properties = "inferqueue.worker.enabled=false")
@Import(QueueRecoveryIntegrationTest.Adapters.class)
class QueueRecoveryIntegrationTest extends IntegrationTestBase {

    @TestConfiguration
    static class Adapters {

        @Bean
        ProgrammableAdapter programmableAdapter() {
            return new ProgrammableAdapter();
        }
    }

    @Autowired
    private JobService jobService;

    @Autowired
    private JobRepository jobs;

    @Autowired
    private JobQueue queue;

    @Autowired
    private DelayedQueue delayedQueue;

    @Autowired
    private JobExecutor executor;

    @Autowired
    private ApiKeyService apiKeys;

    @Autowired
    private DeadLetterService deadLetters;

    @Autowired
    private ProgrammableAdapter adapter;

    private ApiKey apiKey;

    @BeforeEach
    void setUp() {
        // Sin workers no hay quien cree los consumer groups; con el pool andando
        // esto lo hace WorkerPool al arrancar.
        queue.ensureConsumerGroups();
        ApiKeyService.IssuedKey issued = apiKeys.issue(UUID.randomUUID(), "recovery-it", Tier.PREMIUM);
        apiKey = apiKeys.authenticate(issued.plaintextKey()).orElseThrow();
    }

    private Job submit(String prompt) {
        return jobService.submit(apiKey, new SubmitJobRequest("llama3", JobType.COMPLETION, prompt, null, null), null)
                .job();
    }

    private JobStatus statusOf(UUID jobId) {
        return jobs.findById(jobId).orElseThrow().getStatus();
    }

    /**
     * Lee del stream como lo haría un worker hasta encontrar el mensaje de este
     * job, y no lo ackea. Los streams son compartidos entre tests, así que lo
     * que sobra de otros se ackea y se descarta para no quedar leyéndolo de nuevo.
     */
    private MapRecord<String, String, String> deliverTo(String worker, UUID jobId) {
        return await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(50))
                .until(() -> {
                    for (MapRecord<String, String, String> record : queue.read(worker, 10)) {
                        if (QueueMessage.fromMap(record.getValue()).jobId().equals(jobId)) {
                            return record;
                        }
                        queue.ack(record.getStream(), record.getId());
                        queue.delete(record.getStream(), record.getId());
                    }
                    return null;
                }, record -> record != null);
    }

    @Test
    @DisplayName("un mensaje entregado y nunca ackeado se reclama con XCLAIM y lo termina otro worker")
    void orphanMessageIsReclaimedByAnotherWorker() {
        Job job = submit("huerfano-" + UUID.randomUUID());

        // El worker recibe el mensaje y muere: nunca hace XACK.
        MapRecord<String, String, String> delivered = deliverTo("worker-que-muere", job.getId());
        assertThat(QueueMessage.fromMap(delivered.getValue()).jobId()).isEqualTo(job.getId());
        assertThat(statusOf(job.getId())).isEqualTo(JobStatus.QUEUED);

        String stream = delivered.getStream();
        // Antes del idle timeout nadie puede robarle el mensaje: sigue siendo suyo.
        assertThat(queue.claimStale(stream, "reclaimer")).isEmpty();

        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            List<MapRecord<String, String, String>> claimed = queue.claimStale(stream, "reclaimer");
            assertThat(claimed).isNotEmpty();
            claimed.forEach(record ->
                    executor.process(stream, record.getId(), QueueMessage.fromMap(record.getValue()), "reclaimer"));
        });

        assertThat(statusOf(job.getId())).isEqualTo(JobStatus.DONE);
        assertThat(jobs.findById(job.getId()).orElseThrow().getClaimedBy()).isEqualTo("reclaimer");
    }

    @Test
    @DisplayName("procesar y ackear saca el mensaje del PEL: no queda nada que reclamar")
    void ackedMessageLeavesThePendingList() {
        Job job = submit("ackeado-" + UUID.randomUUID());

        MapRecord<String, String, String> delivered = deliverTo("worker-sano", job.getId());
        String stream = delivered.getStream();
        assertThat(queue.pendingCount(Priority.PRIORITY) + queue.pendingCount(Priority.STANDARD)).isPositive();

        executor.process(stream, delivered.getId(), QueueMessage.fromMap(delivered.getValue()), "worker-sano");

        assertThat(statusOf(job.getId())).isEqualTo(JobStatus.DONE);
        // Ya ackeado: aunque pase el idle timeout, no hay huérfano que reclamar.
        await().atMost(Duration.ofSeconds(5)).pollDelay(Duration.ofSeconds(2)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(queue.claimStale(stream, "reclaimer")).isEmpty());
    }

    @Test
    @DisplayName("un retry diferido no está en el stream hasta que vence su backoff")
    void delayedRetryIsPromotedOnlyWhenDue() {
        Job job = submit("diferido-" + UUID.randomUUID());
        // Sacamos el mensaje original de la cola para que no interfiera.
        MapRecord<String, String, String> original = deliverTo("worker-inicial", job.getId());
        queue.ack(original.getStream(), original.getId());
        queue.delete(original.getStream(), original.getId());

        QueueMessage retry = new QueueMessage(job.getId(), Priority.PRIORITY, 1, System.currentTimeMillis());
        long depthBefore = queue.depth(Priority.PRIORITY);
        delayedQueue.scheduleRetry(retry);

        // Todavía no vencido: promover no encola nada.
        delayedQueue.promoteDue();
        assertThat(queue.depth(Priority.PRIORITY)).isEqualTo(depthBefore);
        assertThat(delayedQueue.size()).isPositive();

        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100)).untilAsserted(() -> {
            delayedQueue.promoteDue();
            assertThat(queue.depth(Priority.PRIORITY)).isGreaterThan(depthBefore);
        });
    }

    @Test
    @DisplayName("cancelar un job saca del ZSET los retries que todavía no llegaron al stream")
    void cancellingDropsPendingRetries() {
        Job job = submit("cancelado-" + UUID.randomUUID());
        delayedQueue.scheduleRetry(new QueueMessage(job.getId(), Priority.PRIORITY, 1, System.currentTimeMillis()));
        long sizeBefore = delayedQueue.size();

        assertThat(jobService.cancel(job.getId())).isPresent();

        assertThat(delayedQueue.size()).isEqualTo(sizeBefore - 1);
        assertThat(statusOf(job.getId())).isEqualTo(JobStatus.CANCELED);
    }

    @Test
    @DisplayName("el barrido de TTL cierra los jobs que vencieron esperando en la cola")
    void ttlSweeperClosesExpiredJobs() {
        Job job = jobService.submit(apiKey,
                new SubmitJobRequest("llama3", JobType.COMPLETION, "efimero-" + UUID.randomUUID(), null, 5), null)
                .job();

        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(500)).untilAsserted(() -> {
            executor.sweepExpired();
            assertThat(statusOf(job.getId())).isEqualTo(JobStatus.EXPIRED);
        });
    }

    @Test
    @DisplayName("un job muerto se reencola desde la DLQ y se procesa como uno nuevo")
    void deadJobIsRequeuedFromTheDeadLetterQueue() {
        String prompt = "muerto-" + UUID.randomUUID();
        Job job = submit(prompt);

        // Lo matamos agotando los intentos: el mensaje llega con attempt al tope.
        MapRecord<String, String, String> delivered = deliverTo("worker-que-lo-mata", job.getId());
        String stream = delivered.getStream();
        QueueMessage exhausted = new QueueMessage(job.getId(), Priority.PRIORITY, 99, System.currentTimeMillis());
        adapter.failTimes(prompt, 99);
        executor.process(stream, delivered.getId(), exhausted, "worker-que-lo-mata");

        assertThat(statusOf(job.getId())).isEqualTo(JobStatus.DEAD);
        var entry = queue.deadLetters(200).stream()
                .filter(candidate -> candidate.jobId().equals(job.getId()))
                .findFirst()
                .orElseThrow();

        Job requeued = deadLetters.requeue(entry.recordId()).orElseThrow();

        assertThat(requeued.getStatus()).isEqualTo(JobStatus.QUEUED);
        assertThat(requeued.getRetryCount()).isZero();
        // La entrada se fue de la DLQ y el mensaje volvió al stream.
        assertThat(queue.deadLetter(entry.recordId())).isEmpty();
        MapRecord<String, String, String> redelivered = deliverTo("worker-nuevo", job.getId());
        assertThat(QueueMessage.fromMap(redelivered.getValue()).jobId()).isEqualTo(job.getId());
    }
}
