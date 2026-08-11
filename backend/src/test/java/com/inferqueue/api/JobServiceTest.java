package com.inferqueue.api;

import com.inferqueue.TestProperties;
import com.inferqueue.domain.ApiKey;
import com.inferqueue.domain.Job;
import com.inferqueue.domain.JobRepository;
import com.inferqueue.domain.JobType;
import com.inferqueue.domain.Priority;
import com.inferqueue.domain.Tier;
import com.inferqueue.queue.DelayedQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JobServiceTest {

    private static final String IDEMPOTENCY_KEY = "cliente-retry-1";

    private final ApiKey apiKey = new ApiKey(UUID.randomUUID(), "cli", "hash", Tier.PREMIUM);
    private final SubmitJobRequest request =
            new SubmitJobRequest("llama3", JobType.COMPLETION, "hola", null, null);

    private JobWriter writer;
    private JobService service;

    @BeforeEach
    void setUp() {
        writer = mock(JobWriter.class);
        service = new JobService(mock(JobRepository.class), writer, mock(DelayedQueue.class),
                TestProperties.defaults(), mock(ApplicationEventPublisher.class));
    }

    private Job existingJob() {
        return new Job(apiKey.getId(), "llama3", JobType.COMPLETION, "hola", Priority.PRIORITY,
                Instant.now().plus(10, ChronoUnit.MINUTES));
    }

    @Test
    @DisplayName("sin Idempotency-Key cada submit crea un job nuevo")
    void submitWithoutKeyAlwaysCreates() {
        Job created = existingJob();
        when(writer.insert(any())).thenReturn(created);

        JobService.Submission submission = service.submit(apiKey, request, null);

        assertThat(submission.replayed()).isFalse();
        assertThat(submission.job()).isEqualTo(created);
        verify(writer, never()).findByIdempotencyKey(any(), any());
    }

    @Test
    @DisplayName("repetir un Idempotency-Key devuelve el job original sin encolar de nuevo")
    void repeatedKeyReplaysOriginalJob() {
        Job original = existingJob();
        when(writer.findByIdempotencyKey(apiKey.getId(), IDEMPOTENCY_KEY)).thenReturn(Optional.of(original));

        JobService.Submission submission = service.submit(apiKey, request, IDEMPOTENCY_KEY);

        assertThat(submission.replayed()).isTrue();
        assertThat(submission.job()).isEqualTo(original);
        verify(writer, never()).insert(any());
    }

    @Test
    @DisplayName("si dos requests con el mismo key corren a la vez, el que pierde el índice único relee el ganador")
    void concurrentSubmitsResolveToTheSameJob() {
        Job winner = existingJob();
        when(writer.findByIdempotencyKey(apiKey.getId(), IDEMPOTENCY_KEY))
                // Primera consulta: todavía no hay nada. Después del choque, sí.
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));
        when(writer.insert(any())).thenThrow(new DataIntegrityViolationException("uq_jobs_idempotency"));

        JobService.Submission submission = service.submit(apiKey, request, IDEMPOTENCY_KEY);

        assertThat(submission.replayed()).isTrue();
        assertThat(submission.job()).isEqualTo(winner);
    }

    @Test
    @DisplayName("una violación de integridad sin Idempotency-Key no se disfraza de idempotencia")
    void integrityViolationWithoutKeyPropagates() {
        when(writer.insert(any())).thenThrow(new DataIntegrityViolationException("otra cosa"));

        assertThatThrownBy(() -> service.submit(apiKey, request, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("el batch entra en un solo insert y deriva un Idempotency-Key por ítem")
    void batchDerivesOneKeyPerItem() {
        when(writer.findByIdempotencyKeys(any(), any())).thenReturn(List.of());
        when(writer.insertAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        JobService.BatchSubmission submission = service.submitBatch(apiKey,
                new BatchSubmitRequest(List.of(request, request, request)), IDEMPOTENCY_KEY);

        assertThat(submission.replayed()).isFalse();
        assertThat(submission.jobs()).hasSize(3);
        assertThat(submission.jobs()).extracting(Job::getIdempotencyKey)
                .containsExactly(IDEMPOTENCY_KEY + "#0", IDEMPOTENCY_KEY + "#1", IDEMPOTENCY_KEY + "#2");
    }

    @Test
    @DisplayName("reintentar un batch ya aceptado devuelve los mismos jobs sin encolar de nuevo")
    void repeatedBatchReplaysOriginalJobs() {
        List<Job> original = List.of(existingJob(), existingJob());
        when(writer.findByIdempotencyKeys(any(), any())).thenReturn(original);

        JobService.BatchSubmission submission = service.submitBatch(apiKey,
                new BatchSubmitRequest(List.of(request, request)), IDEMPOTENCY_KEY);

        assertThat(submission.replayed()).isTrue();
        assertThat(submission.jobs()).isEqualTo(original);
        verify(writer, never()).insertAll(any());
    }

    @Test
    @DisplayName("un batch sin Idempotency-Key no consulta por claves derivadas")
    void batchWithoutKeyGoesStraightToInsert() {
        when(writer.insertAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        JobService.BatchSubmission submission = service.submitBatch(apiKey,
                new BatchSubmitRequest(List.of(request)), null);

        assertThat(submission.replayed()).isFalse();
        assertThat(submission.jobs()).singleElement()
                .extracting(Job::getIdempotencyKey).isNull();
        verify(writer, never()).findByIdempotencyKeys(any(), any());
    }

    @Test
    @DisplayName("el job hereda la prioridad del tier cuando el request no la especifica")
    void priorityFallsBackToTier() {
        when(writer.insert(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Job premium = service.submit(apiKey, request, null).job();
        assertThat(premium.getPriority()).isEqualTo(Priority.PRIORITY);

        ApiKey free = new ApiKey(UUID.randomUUID(), "free", "hash2", Tier.FREE);
        assertThat(service.submit(free, request, null).job().getPriority()).isEqualTo(Priority.STANDARD);
    }
}
