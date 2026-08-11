package com.inferqueue.it;

import com.inferqueue.api.JobService;
import com.inferqueue.api.SubmitJobRequest;
import com.inferqueue.domain.ApiKey;
import com.inferqueue.domain.Job;
import com.inferqueue.domain.JobRepository;
import com.inferqueue.domain.JobStatus;
import com.inferqueue.domain.JobType;
import com.inferqueue.domain.Priority;
import com.inferqueue.domain.Tier;
import com.inferqueue.queue.DeadLetterEntry;
import com.inferqueue.queue.JobQueue;
import com.inferqueue.security.ApiKeyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Ciclo de vida completo con Redis y Postgres reales: el job cruza la cola, lo
 * toma un worker del pool y termina persistido. Lo que se está verificando es el
 * cableado entero, no una pieza — es el test que se rompe si el XADD queda antes
 * del commit o si el consumer group no se creó.
 */
@SpringBootTest
@Import(JobLifecycleIntegrationTest.Adapters.class)
class JobLifecycleIntegrationTest extends IntegrationTestBase {

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
    private ApiKeyService apiKeys;

    @Autowired
    private ProgrammableAdapter adapter;

    private ApiKey apiKey;

    @BeforeEach
    void issueKey() {
        ApiKeyService.IssuedKey issued = apiKeys.issue(UUID.randomUUID(), "it", Tier.PREMIUM);
        apiKey = apiKeys.authenticate(issued.plaintextKey()).orElseThrow();
    }

    private Job submit(String prompt) {
        return jobService.submit(apiKey, new SubmitJobRequest("llama3", JobType.COMPLETION, prompt, null, null), null)
                .job();
    }

    private JobStatus statusOf(UUID jobId) {
        return jobs.findById(jobId).orElseThrow().getStatus();
    }

    private void awaitStatus(UUID jobId, JobStatus expected) {
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> assertThat(statusOf(jobId)).isEqualTo(expected));
    }

    @Test
    @DisplayName("un job encolado lo toma un worker del pool y queda persistido con su resultado")
    void jobFlowsThroughTheQueueAndCompletes() {
        Job job = submit("hola-" + UUID.randomUUID());

        awaitStatus(job.getId(), JobStatus.DONE);

        Job done = jobs.findById(job.getId()).orElseThrow();
        assertThat(done.getResult()).startsWith("resultado de ");
        assertThat(done.getTokensUsed()).isPositive();
        assertThat(done.getClaimedBy()).isNotBlank();
        assertThat(done.getCompletedAt()).isNotNull();
    }

    @Test
    @DisplayName("un fallo transitorio se reintenta con backoff y el job termina bien")
    void transientFailureIsRetriedAndSucceeds() {
        String prompt = "flaky-" + UUID.randomUUID();
        adapter.failTimes(prompt, 1);

        Job job = submit(prompt);

        awaitStatus(job.getId(), JobStatus.DONE);
        // Dos ejecuciones: la que falló y la del reintento promovido desde el ZSET.
        assertThat(adapter.callsFor(prompt)).isEqualTo(2);
        assertThat(jobs.findById(job.getId()).orElseThrow().getRetryCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("al agotar los reintentos el job queda DEAD y aparece en la DLQ con su motivo")
    void exhaustedRetriesLandInTheDeadLetterQueue() {
        String prompt = "siempre-falla-" + UUID.randomUUID();
        adapter.failTimes(prompt, 99);

        Job job = submit(prompt);

        awaitStatus(job.getId(), JobStatus.DEAD);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            List<DeadLetterEntry> entries = queue.deadLetters(200);
            assertThat(entries).anyMatch(entry -> entry.jobId().equals(job.getId())
                    && entry.reason().contains(prompt));
        });
    }

    @Test
    @DisplayName("un error no reintentable va directo a la DLQ sin gastar reintentos")
    void nonRetryableFailsImmediately() {
        String prompt = "boom:" + UUID.randomUUID();

        Job job = submit(prompt);

        awaitStatus(job.getId(), JobStatus.DEAD);
        assertThat(adapter.callsFor(prompt)).isEqualTo(1);
    }

    @Test
    @DisplayName("cancelar un job en pleno vuelo descarta su resultado en vez de pisarlo")
    void cancellingWhileProcessingDiscardsTheResult() throws Exception {
        String prompt = "lento-" + UUID.randomUUID();
        CountDownLatch gate = adapter.gate(prompt);

        Job job = submit(prompt);
        // Esperamos a que el worker lo tenga tomado y frenado en la compuerta.
        awaitStatus(job.getId(), JobStatus.PROCESSING);

        assertThat(jobService.cancel(job.getId())).isPresent();
        gate.countDown();

        // El worker termina la inference y su markDone tiene que ser un no-op.
        Thread.sleep(500);
        Job canceled = jobs.findById(job.getId()).orElseThrow();
        assertThat(canceled.getStatus()).isEqualTo(JobStatus.CANCELED);
        assertThat(canceled.getResult()).isNull();
    }

    @Test
    @DisplayName("el índice único de idempotencia frena dos submits simultáneos con la misma clave")
    void concurrentIdempotentSubmitsCreateOneJob() throws Exception {
        String key = "idem-" + UUID.randomUUID();
        SubmitJobRequest request = new SubmitJobRequest("llama3", JobType.COMPLETION, "dedup", null, null);

        int racers = 8;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(racers);
        UUID[] results = new UUID[racers];

        for (int i = 0; i < racers; i++) {
            int index = i;
            Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    results[index] = jobService.submit(apiKey, request, key).job().getId();
                } catch (Exception e) {
                    results[index] = null;
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        // Todos los que respondieron devolvieron el mismo job, y hay uno solo en la base.
        assertThat(results).doesNotContainNull();
        assertThat(results).containsOnly(results[0]);
        assertThat(jobs.findByApiKeyIdAndIdempotencyKey(apiKey.getId(), key)).isPresent();
    }

    @Test
    @DisplayName("el batch encola todo en una transacción y los workers lo procesan")
    void batchIsEnqueuedAndProcessed() {
        String tag = UUID.randomUUID().toString();
        List<SubmitJobRequest> items = List.of(
                new SubmitJobRequest("llama3", JobType.COMPLETION, "b1-" + tag, Priority.STANDARD, null),
                new SubmitJobRequest("llama3", JobType.COMPLETION, "b2-" + tag, Priority.PRIORITY, null),
                new SubmitJobRequest("llama3", JobType.EMBEDDING, "b3-" + tag, null, null));

        JobService.BatchSubmission submission =
                jobService.submitBatch(apiKey, new com.inferqueue.api.BatchSubmitRequest(items), "batch-" + tag);

        assertThat(submission.replayed()).isFalse();
        assertThat(submission.jobs()).hasSize(3);
        submission.jobs().forEach(job -> awaitStatus(job.getId(), JobStatus.DONE));

        // Reintentar el mismo batch devuelve los mismos jobs, no tres nuevos.
        JobService.BatchSubmission replay =
                jobService.submitBatch(apiKey, new com.inferqueue.api.BatchSubmitRequest(items), "batch-" + tag);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.jobs()).extracting(Job::getId)
                .containsExactlyInAnyOrderElementsOf(submission.jobs().stream().map(Job::getId).toList());
    }
}
