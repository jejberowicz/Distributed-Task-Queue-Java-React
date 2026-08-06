package com.inferqueue.worker;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Adapter determinístico-ish para desarrollo y tests: simula latencia y una
 * tasa de fallo, así se puede ver el retry/backoff y la DLQ sin Ollama.
 * Un prompt que empieza con "fail:" falla siempre — útil para demos.
 */
@Component
@ConditionalOnProperty(name = "inferqueue.ollama.adapter", havingValue = "mock", matchIfMissing = true)
public class MockAdapter implements ModelAdapter {

    private static final double FAILURE_RATE = 0.15;

    @Override
    public InferenceResult infer(InferenceRequest request) {
        long latencyMillis = switch (request.type()) {
            case EMBEDDING -> ThreadLocalRandom.current().nextLong(150, 600);
            case CLASSIFICATION -> ThreadLocalRandom.current().nextLong(400, 1500);
            case COMPLETION -> ThreadLocalRandom.current().nextLong(1200, 4000);
        };
        try {
            Thread.sleep(latencyMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InferenceException("Inference interrumpida", e, true);
        }

        if (request.prompt().startsWith("fail:")) {
            throw new InferenceException("Fallo forzado por el prompt", true);
        }
        if (ThreadLocalRandom.current().nextDouble() < FAILURE_RATE) {
            throw new InferenceException("Backend simulado no disponible", true);
        }

        int tokens = Math.max(1, request.prompt().length() / 4);
        String output = switch (request.type()) {
            case EMBEDDING -> fakeEmbedding(request.prompt());
            case CLASSIFICATION -> ThreadLocalRandom.current().nextBoolean() ? "positive" : "negative";
            case COMPLETION -> "[mock:%s] %s".formatted(request.model(), request.prompt().toUpperCase());
        };
        return new InferenceResult(output, tokens);
    }

    private String fakeEmbedding(String prompt) {
        StringBuilder sb = new StringBuilder("[");
        int seed = prompt.hashCode();
        for (int i = 0; i < 8; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append("%.4f".formatted(((seed >> i) % 1000) / 1000.0));
        }
        return sb.append("]").toString();
    }

    @Override
    public String name() {
        return "mock";
    }
}
