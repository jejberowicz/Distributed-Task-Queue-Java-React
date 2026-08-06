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
            Duration defaultTtl
    ) {
    }

    public record Streams(String priority, String standard) {
    }

    public record Worker(int concurrency, Duration pollTimeout) {
    }

    public record RateLimit(Bucket free, Bucket premium) {
    }

    public record Bucket(int requestsPerMinute) {
    }

    public record Ollama(String baseUrl, Duration timeout, String adapter) {
    }
}
