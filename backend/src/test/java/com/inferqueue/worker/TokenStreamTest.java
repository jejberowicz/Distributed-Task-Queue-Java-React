package com.inferqueue.worker;

import com.inferqueue.domain.Job;
import com.inferqueue.domain.JobTokenEvent;
import com.inferqueue.domain.JobType;
import com.inferqueue.domain.Priority;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TokenStreamTest {

    private final Job job = new Job(UUID.randomUUID(), "llama3", JobType.COMPLETION, "hola", Priority.STANDARD,
            Instant.now().plus(5, ChronoUnit.MINUTES));
    private final List<JobTokenEvent> published = new ArrayList<>();
    private final ApplicationEventPublisher events = event -> published.add((JobTokenEvent) event);

    @Test
    @DisplayName("los fragmentos chicos se acumulan en vez de publicar uno por token")
    void smallChunksAreBuffered() {
        TokenStream stream = new TokenStream(job, events);

        stream.emit("hola ");
        stream.emit("mundo");

        assertThat(published).isEmpty();
    }

    @Test
    @DisplayName("el buffer se vacía al superar el umbral de tamaño")
    void bufferFlushesWhenFull() {
        TokenStream stream = new TokenStream(job, events);

        for (int i = 0; i < 10; i++) {
            stream.emit("0123456789");
        }

        assertThat(published).isNotEmpty();
        assertThat(published.get(0).jobId()).isEqualTo(job.getId());
        assertThat(published.get(0).apiKeyId()).isEqualTo(job.getApiKeyId());
    }

    @Test
    @DisplayName("flush() emite lo que quedó y numera los fragmentos en orden")
    void finalFlushEmitsRemainder() {
        TokenStream stream = new TokenStream(job, events);

        stream.emit("primero");
        stream.flush();
        stream.emit("segundo");
        stream.flush();

        assertThat(published).hasSize(2);
        assertThat(published).extracting(JobTokenEvent::chunk).containsExactly("primero", "segundo");
        assertThat(published).extracting(JobTokenEvent::seq).containsExactly(0, 1);
    }

    @Test
    @DisplayName("un flush con el buffer vacío no publica nada")
    void emptyFlushIsNoOp() {
        TokenStream stream = new TokenStream(job, events);

        stream.emit(null);
        stream.emit("");
        stream.flush();

        assertThat(published).isEmpty();
    }
}
