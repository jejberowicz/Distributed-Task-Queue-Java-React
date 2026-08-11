package com.inferqueue.queue;

import com.inferqueue.config.InferQueueProperties;
import com.inferqueue.domain.Priority;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Retención de los streams. El camino feliz ya limpia solo — cada mensaje se
 * borra después del XACK — pero un ack perdido, un XADD que nunca se consumió o
 * un consumer group borrado dejan entradas que nadie va a sacar. Sin un techo,
 * Redis crece hasta quedarse sin memoria.
 *
 * <p>XTRIM MAXLEN descarta las entradas <em>más viejas</em>, que en un stream de
 * trabajo son las que todavía no se procesaron. Por eso el límite se configura
 * muy por encima del backlog esperable y cada recorte efectivo se loguea como
 * warning: si esto está recortando de verdad, el problema no es la retención
 * sino que los workers no dan abasto.
 */
@Component
public class StreamTrimmer {

    private static final Logger log = LoggerFactory.getLogger(StreamTrimmer.class);

    private final JobQueue queue;
    private final InferQueueProperties props;

    public StreamTrimmer(JobQueue queue, InferQueueProperties props) {
        this.queue = queue;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${inferqueue.queue.retention.trim-interval}")
    public void trim() {
        InferQueueProperties.Retention retention = props.queue().retention();
        for (Priority priority : Priority.values()) {
            trimIfOverLimit(queue.streamFor(priority), queue.depth(priority), retention.maxStreamLength());
        }
        trimIfOverLimit(props.queue().dlqStream(), queue.deadLetterDepth(), retention.maxDlqLength());
    }

    private void trimIfOverLimit(String stream, long length, long max) {
        if (length <= max) {
            return;
        }
        long remaining = queue.trim(stream, max);
        log.warn("Stream {} recortado de {} a ~{} entradas: se descartaron mensajes sin procesar", stream, length,
                remaining);
    }
}
