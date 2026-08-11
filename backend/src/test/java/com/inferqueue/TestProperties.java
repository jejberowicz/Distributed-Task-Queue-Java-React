package com.inferqueue;

import com.inferqueue.config.InferQueueProperties;

import java.time.Duration;

/** Configuración fija para los tests unitarios. */
public final class TestProperties {

    private TestProperties() {
    }

    public static InferQueueProperties defaults() {
        return new InferQueueProperties(
                new InferQueueProperties.Queue(
                        new InferQueueProperties.Streams("infer:priority", "infer:standard"),
                        "infer:dlq",
                        "inferqueue-workers",
                        Duration.ofSeconds(60),
                        Duration.ofSeconds(15),
                        32,
                        3,
                        Duration.ofSeconds(2),
                        Duration.ofMinutes(5),
                        Duration.ofMinutes(30),
                        new InferQueueProperties.Retention(100_000, 10_000, Duration.ofMinutes(5))),
                new InferQueueProperties.Worker(true, 2, Duration.ofSeconds(2)),
                new InferQueueProperties.RateLimit(
                        new InferQueueProperties.Bucket(20),
                        new InferQueueProperties.Bucket(300)),
                new InferQueueProperties.Ollama("http://localhost:11434", Duration.ofSeconds(120), "mock"));
    }
}
