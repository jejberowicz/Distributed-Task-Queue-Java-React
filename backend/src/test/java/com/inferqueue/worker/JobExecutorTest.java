package com.inferqueue.worker;

import com.inferqueue.TestProperties;
import com.inferqueue.domain.Job;
import com.inferqueue.domain.JobType;
import com.inferqueue.domain.Priority;
import com.inferqueue.metrics.QueueMetrics;
import com.inferqueue.queue.DelayedQueue;
import com.inferqueue.queue.JobQueue;
import com.inferqueue.queue.QueueMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.stream.RecordId;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JobExecutorTest {

    private static final String STREAM = "infer:standard";
    private static final RecordId RECORD_ID = RecordId.of("1700000000000-0");
    private static final String WORKER = "worker-0";

    private JobStateService state;
    private JobQueue queue;
    private DelayedQueue delayedQueue;
    private ModelAdapter adapter;
    private JobExecutor executor;

    @BeforeEach
    void setUp() {
        state = mock(JobStateService.class);
        queue = mock(JobQueue.class);
        delayedQueue = mock(DelayedQueue.class);
        adapter = mock(ModelAdapter.class);
        executor = new JobExecutor(state, queue, delayedQueue, adapter, TestProperties.defaults(),
                mock(QueueMetrics.class));
    }

    private Job pendingJob() {
        return new Job(UUID.randomUUID(), "llama3", JobType.COMPLETION, "hola", Priority.STANDARD,
                Instant.now().plus(10, ChronoUnit.MINUTES));
    }

    /** Por defecto todas las transiciones prosperan: el job sigue disponible. */
    private void transitionsSucceed(Job job) {
        when(state.markProcessing(eq(job.getId()), anyString(), anyString())).thenReturn(Optional.of(job));
        when(state.markDone(eq(job.getId()), any(), any())).thenReturn(Optional.of(job));
        when(state.markRetrying(eq(job.getId()), anyString())).thenReturn(Optional.of(job));
        when(state.markDead(eq(job.getId()), anyString())).thenReturn(Optional.of(job));
        when(state.markExpired(job.getId())).thenReturn(Optional.of(job));
    }

    @Test
    @DisplayName("un job exitoso se completa y recién ahí se ackea")
    void completesAndAcks() {
        Job job = pendingJob();
        when(state.find(job.getId())).thenReturn(Optional.of(job));
        transitionsSucceed(job);
        when(adapter.infer(any())).thenReturn(new InferenceResult("respuesta", 42));

        executor.process(STREAM, RECORD_ID, QueueMessage.first(job.getId(), Priority.STANDARD), WORKER);

        verify(state).markProcessing(job.getId(), WORKER, RECORD_ID.getValue());
        verify(state).markDone(job.getId(), "respuesta", 42);
        verify(queue).ack(STREAM, RECORD_ID);
        verify(delayedQueue, never()).scheduleRetry(any());
    }

    @Test
    @DisplayName("una falla reintentable programa el retry con backoff en vez de ejecutar de nuevo en el acto")
    void schedulesRetry() {
        Job job = pendingJob();
        when(state.find(job.getId())).thenReturn(Optional.of(job));
        transitionsSucceed(job);
        when(adapter.infer(any())).thenThrow(new InferenceException("backend caído", true));

        QueueMessage message = QueueMessage.first(job.getId(), Priority.STANDARD);
        executor.process(STREAM, RECORD_ID, message, WORKER);

        verify(state).markRetrying(eq(job.getId()), anyString());
        // El mensaje se ackea para que no quede en el PEL: el retry es un mensaje nuevo.
        verify(queue).ack(STREAM, RECORD_ID);
        verify(delayedQueue).scheduleRetry(argThatAttemptIs(1));
        verify(queue, never()).toDeadLetter(any(), anyString());
    }

    @Test
    @DisplayName("al agotar los reintentos el job termina en la dead-letter queue")
    void sendsToDeadLetterAfterMaxRetries() {
        Job job = pendingJob();
        when(state.find(job.getId())).thenReturn(Optional.of(job));
        transitionsSucceed(job);
        when(adapter.infer(any())).thenThrow(new InferenceException("sigue fallando", true));

        // maxRetries=3, así que el intento 3 (nextAttempt=4) ya no se reintenta.
        QueueMessage exhausted = new QueueMessage(job.getId(), Priority.STANDARD, 3, System.currentTimeMillis());
        executor.process(STREAM, RECORD_ID, exhausted, WORKER);

        verify(state).markDead(eq(job.getId()), anyString());
        verify(queue).toDeadLetter(eq(exhausted), anyString());
        verify(queue).ack(STREAM, RECORD_ID);
        verify(delayedQueue, never()).scheduleRetry(any());
    }

    @Test
    @DisplayName("un error no reintentable va directo a la DLQ")
    void nonRetryableGoesStraightToDlq() {
        Job job = pendingJob();
        when(state.find(job.getId())).thenReturn(Optional.of(job));
        transitionsSucceed(job);
        when(adapter.infer(any())).thenThrow(new InferenceException("modelo inexistente", false));

        executor.process(STREAM, RECORD_ID, QueueMessage.first(job.getId(), Priority.STANDARD), WORKER);

        verify(state).markDead(eq(job.getId()), anyString());
        verify(delayedQueue, never()).scheduleRetry(any());
    }

    @Test
    @DisplayName("at-least-once: la reentrega de un job ya terminado se ackea sin reejecutar")
    void redeliveryOfTerminalJobIsNoOp() {
        Job job = pendingJob();
        job.markProcessing(WORKER);
        job.markDone("ya estaba listo", 10);
        when(state.find(job.getId())).thenReturn(Optional.of(job));

        executor.process(STREAM, RECORD_ID, QueueMessage.first(job.getId(), Priority.STANDARD), WORKER);

        verify(adapter, never()).infer(any());
        verify(state, never()).markProcessing(any(), anyString(), anyString());
        verify(queue).ack(STREAM, RECORD_ID);
    }

    @Test
    @DisplayName("un job vencido no se ejecuta: se marca EXPIRED y se ackea")
    void expiredJobIsNotExecuted() {
        Job job = new Job(UUID.randomUUID(), "llama3", JobType.COMPLETION, "tarde", Priority.STANDARD,
                Instant.now().minusSeconds(1));
        when(state.find(job.getId())).thenReturn(Optional.of(job));
        transitionsSucceed(job);

        executor.process(STREAM, RECORD_ID, QueueMessage.first(job.getId(), Priority.STANDARD), WORKER);

        verify(state).markExpired(job.getId());
        verify(adapter, never()).infer(any());
        verify(queue).ack(STREAM, RECORD_ID);
    }

    @Test
    @DisplayName("un mensaje que apunta a un job inexistente se descarta sin romper la cola")
    void unknownJobIsDiscarded() {
        UUID unknown = UUID.randomUUID();
        when(state.find(unknown)).thenReturn(Optional.empty());

        executor.process(STREAM, RECORD_ID, QueueMessage.first(unknown, Priority.STANDARD), WORKER);

        verify(queue).ack(STREAM, RECORD_ID);
        verify(adapter, never()).infer(any());
    }

    @Test
    @DisplayName("si el job se cancela durante la inference, el resultado se descarta")
    void resultOfCanceledJobIsDiscarded() {
        Job job = pendingJob();
        when(state.find(job.getId())).thenReturn(Optional.of(job));
        when(state.markProcessing(eq(job.getId()), anyString(), anyString())).thenReturn(Optional.of(job));
        // markDone vacío = la transición no prosperó porque el job ya está terminal.
        when(state.markDone(eq(job.getId()), any(), any())).thenReturn(Optional.empty());
        when(adapter.infer(any())).thenReturn(new InferenceResult("respuesta tardía", 42));

        executor.process(STREAM, RECORD_ID, QueueMessage.first(job.getId(), Priority.STANDARD), WORKER);

        // El mensaje igual se saca de la cola: el job está cerrado, no hay nada que reintentar.
        verify(queue).ack(STREAM, RECORD_ID);
        verify(delayedQueue, never()).scheduleRetry(any());
    }

    @Test
    @DisplayName("un job cancelado antes de arrancar no llega a ejecutarse")
    void canceledBeforeStartIsNotExecuted() {
        Job job = pendingJob();
        when(state.find(job.getId())).thenReturn(Optional.of(job));
        when(state.markProcessing(eq(job.getId()), anyString(), anyString())).thenReturn(Optional.empty());

        executor.process(STREAM, RECORD_ID, QueueMessage.first(job.getId(), Priority.STANDARD), WORKER);

        verify(adapter, never()).infer(any());
        verify(queue).ack(STREAM, RECORD_ID);
    }

    @Test
    @DisplayName("un job cancelado que falla no programa el retry")
    void canceledJobDoesNotScheduleRetry() {
        Job job = pendingJob();
        when(state.find(job.getId())).thenReturn(Optional.of(job));
        when(state.markProcessing(eq(job.getId()), anyString(), anyString())).thenReturn(Optional.of(job));
        when(state.markRetrying(eq(job.getId()), anyString())).thenReturn(Optional.empty());
        when(adapter.infer(any())).thenThrow(new InferenceException("backend caído", true));

        executor.process(STREAM, RECORD_ID, QueueMessage.first(job.getId(), Priority.STANDARD), WORKER);

        verify(delayedQueue, never()).scheduleRetry(any());
        verify(queue).ack(STREAM, RECORD_ID);
    }

    private QueueMessage argThatAttemptIs(int attempt) {
        return org.mockito.ArgumentMatchers.argThat(message -> message != null && message.attempt() == attempt);
    }
}
