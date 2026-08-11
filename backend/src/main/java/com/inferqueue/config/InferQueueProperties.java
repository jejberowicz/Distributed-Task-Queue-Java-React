package com.inferqueue.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "inferqueue")
public record InferQueueProperties(
        Queue queue,
        Worker worker,
        RateLimit ratelimit,
        Ollama ollama
) {

    public record Queue(
            Streams streams,
            String dlqStream,
            String consumerGroup,
            Duration claimIdleTimeout,
            Duration reclaimInterval,
            int reclaimBatchSize,
            int maxRetries,
            Duration retryBackoffBase,
            Duration retryBackoffMax,
            Duration defaultTtl,
            Retention retention
    ) {
    }

    public record Streams(String priority, String standard) {
    }

    /**
     * Techo de entradas por stream. Los mensajes se borran al ackearse, pero un
     * ack perdido o un XADD que nunca se consumió dejan residuo que no se limpia
     * solo; el trim es la red de contención para que Redis no crezca sin fin.
     */
    public record Retention(long maxStreamLength, long maxDlqLength, Duration trimInterval) {
    }

    /** enabled=false deja la instancia como gateway puro: expone la API pero no consume la cola. */
    public record Worker(boolean enabled, int concurrency, Duration pollTimeout) {
    }

    public record RateLimit(Bucket free, Bucket premium) {
    }

    public record Bucket(int requestsPerMinute) {
    }

    public record Ollama(String baseUrl, Duration timeout, String adapter) {
    }
}
