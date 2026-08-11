package com.inferqueue.api;

import com.inferqueue.TestProperties;
import com.inferqueue.domain.Job;
import com.inferqueue.domain.JobRepository;
import com.inferqueue.domain.JobStatus;
import com.inferqueue.domain.JobType;
import com.inferqueue.domain.Priority;
import com.inferqueue.queue.DeadLetterEntry;
import com.inferqueue.queue.JobQueue;
import com.inferqueue.queue.QueueMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeadLetterServiceTest {

    private static final String RECORD_ID = "1700000000000-0";

    private JobQueue queue;
    private JobRepository jobs;
    private DeadLetterService service;

    @BeforeEach
    void setUp() {
        queue = mock(JobQueue.class);
        jobs = mock(JobRepository.class);
        service = new DeadLetterService(queue, jobs, TestProperties.defaults(),
                mock(ApplicationEventPublisher.class));
    }

    private Job deadJob() {
        Job job = new Job(UUID.randomUUID(), "llama3", JobType.COMPLETION, "hola", Priority.STANDARD,
                // TTL ya vencido: es lo normal en un job que estuvo muerto un rato.
                Instant.now().minusSeconds(3600));
        job.markProcessing("worker-0");
        job.markRetrying("falla 1");
        job.markRetrying("falla 2");
        job.markDead("backend caído");
        return job;
    }

    private DeadLetterEntry entryFor(Job job) {
        return new DeadLetterEntry(RECORD_ID, job.getId(), Priority.STANDARD, 3, "backend caído", Instant.now());
    }

    @Test
    @DisplayName("reencolar reinicia los intentos y renueva el TTL, si no el job vuelve a morir enseguida")
    void requeueResetsAttemptsAndTtl() {
        Job job = deadJob();
        when(queue.deadLetter(RECORD_ID)).thenReturn(Optional.of(entryFor(job)));
        when(jobs.findByIdForUpdate(job.getId())).thenReturn(Optional.of(job));
        when(jobs.save(any(Job.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Job requeued = service.requeue(RECORD_ID).orElseThrow();

        assertThat(requeued.getStatus()).isEqualTo(JobStatus.QUEUED);
        assertThat(requeued.getRetryCount()).isZero();
        assertThat(requeued.getError()).isNull();
        assertThat(requeued.isExpired(Instant.now())).isFalse();
        // Sin transacción activa el afterCommit corre en el acto.
        verify(queue).enqueue(any(QueueMessage.class));
        verify(queue).removeDeadLetter(RECORD_ID);
    }

    @Test
    @DisplayName("una entrada de DLQ inexistente no reencola nada")
    void unknownEntryIsNoOp() {
        when(queue.deadLetter(RECORD_ID)).thenReturn(Optional.empty());

        assertThat(service.requeue(RECORD_ID)).isEmpty();
        verify(queue, never()).enqueue(any());
    }

    @Test
    @DisplayName("una entrada huérfana (el job ya no está en la base) se limpia sin encolar")
    void orphanEntryIsDiscarded() {
        Job job = deadJob();
        when(queue.deadLetter(RECORD_ID)).thenReturn(Optional.of(entryFor(job)));
        when(jobs.findByIdForUpdate(job.getId())).thenReturn(Optional.empty());

        assertThat(service.requeue(RECORD_ID)).isEmpty();
        verify(queue).removeDeadLetter(RECORD_ID);
        verify(queue, never()).enqueue(any());
    }

    @Test
    @DisplayName("reencolar dos veces la misma entrada no deja dos mensajes vivos del mismo job")
    void doubleRequeueDoesNotDuplicate() {
        Job job = deadJob();
        job.requeue(Instant.now().plusSeconds(600));
        when(queue.deadLetter(RECORD_ID)).thenReturn(Optional.of(entryFor(job)));
        when(jobs.findByIdForUpdate(job.getId())).thenReturn(Optional.of(job));

        assertThat(service.requeue(RECORD_ID)).isEmpty();
        verify(queue, never()).enqueue(any());
        verify(queue).removeDeadLetter(RECORD_ID);
    }
}
