package com.inferqueue.worker;

import com.inferqueue.domain.Job;
import com.inferqueue.domain.JobTokenEvent;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Buffer de salida entre el modelo y el dashboard. Publicar un mensaje de
 * pub/sub por token generado inunda Redis y el WebSocket sin que se note en
 * pantalla, así que los fragmentos se acumulan y se emiten por tamaño o por
 * tiempo — lo que ocurra primero.
 *
 * <p>No es thread-safe y no hace falta que lo sea: cada instancia pertenece a la
 * ejecución de un job, que corre en un solo virtual thread.
 */
class TokenStream implements TokenSink {

    private static final int FLUSH_CHARS = 48;
    private static final long FLUSH_INTERVAL_MILLIS = 250;

    private final Job job;
    private final ApplicationEventPublisher events;
    private final StringBuilder pending = new StringBuilder();

    private long lastFlushMillis = System.currentTimeMillis();
    private int seq;

    TokenStream(Job job, ApplicationEventPublisher events) {
        this.job = job;
        this.events = events;
    }

    @Override
    public void emit(String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return;
        }
        pending.append(chunk);
        boolean bigEnough = pending.length() >= FLUSH_CHARS;
        boolean oldEnough = System.currentTimeMillis() - lastFlushMillis >= FLUSH_INTERVAL_MILLIS;
        if (bigEnough || oldEnough) {
            flush();
        }
    }

    /** Vacía lo que quedó en el buffer. Se llama al terminar la inference. */
    void flush() {
        if (pending.isEmpty()) {
            return;
        }
        events.publishEvent(new JobTokenEvent(job.getId(), job.getApiKeyId(), seq++, pending.toString()));
        pending.setLength(0);
        lastFlushMillis = System.currentTimeMillis();
    }
}
