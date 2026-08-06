package com.inferqueue.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JobTest {

    private Job job() {
        return new Job(UUID.randomUUID(), "llama3", JobType.COMPLETION, "hola", Priority.STANDARD,
                Instant.now().plus(5, ChronoUnit.MINUTES));
    }

    @Test
    void arrancaEncoladoSinReintentos() {
        Job job = job();
        assertThat(job.getStatus()).isEqualTo(JobStatus.QUEUED);
        assertThat(job.getRetryCount()).isZero();
        assertThat(job.getStatus().isTerminal()).isFalse();
    }

    @Test
    void completarLimpiaElErrorPrevio() {
        Job job = job();
        job.markRetrying("timeout");
        assertThat(job.getRetryCount()).isEqualTo(1);

        job.markDone("resultado", 12);
        assertThat(job.getStatus()).isEqualTo(JobStatus.DONE);
        assertThat(job.getError()).isNull();
        assertThat(job.getTokensUsed()).isEqualTo(12);
        assertThat(job.getCompletedAt()).isNotNull();
    }

    @Test
    void doneDeadYExpiredSonTerminales() {
        assertThat(JobStatus.DONE.isTerminal()).isTrue();
        assertThat(JobStatus.DEAD.isTerminal()).isTrue();
        assertThat(JobStatus.EXPIRED.isTerminal()).isTrue();
        // FAILED no es terminal: todavía le pueden quedar reintentos.
        assertThat(JobStatus.FAILED.isTerminal()).isFalse();
        assertThat(JobStatus.PROCESSING.isTerminal()).isFalse();
    }

    @Test
    void expiraDespuesDelTtl() {
        Job job = new Job(UUID.randomUUID(), "llama3", JobType.EMBEDDING, "x", Priority.PRIORITY,
                Instant.now().minusSeconds(1));
        assertThat(job.isExpired(Instant.now())).isTrue();
    }

    @Test
    void elTierPremiumEncolaConPrioridad() {
        assertThat(Tier.PREMIUM.defaultPriority()).isEqualTo(Priority.PRIORITY);
        assertThat(Tier.FREE.defaultPriority()).isEqualTo(Priority.STANDARD);
    }
}
