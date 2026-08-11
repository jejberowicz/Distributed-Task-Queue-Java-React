package com.inferqueue.it;

import com.inferqueue.worker.InferenceException;
import com.inferqueue.worker.InferenceRequest;
import com.inferqueue.worker.InferenceResult;
import com.inferqueue.worker.ModelAdapter;
import com.inferqueue.worker.TokenSink;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Adapter de test con el comportamiento programado por prompt. El MockAdapter
 * real falla al azar el 15% de las veces, que es justo lo que no queremos en un
 * test de integración: acá cada prompt dice exactamente qué tiene que pasar.
 */
class ProgrammableAdapter implements ModelAdapter {

    /** Cuántas veces más tiene que fallar cada prompt antes de andar. */
    private final Map<String, AtomicInteger> failuresLeft = new ConcurrentHashMap<>();
    private final Map<String, CountDownLatch> gates = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();

    void failTimes(String prompt, int times) {
        failuresLeft.put(prompt, new AtomicInteger(times));
    }

    /** Bloquea la inference de un prompt hasta que se abra la compuerta. */
    CountDownLatch gate(String prompt) {
        CountDownLatch gate = new CountDownLatch(1);
        gates.put(prompt, gate);
        return gate;
    }

    int callsFor(String prompt) {
        AtomicInteger counter = calls.get(prompt);
        return counter == null ? 0 : counter.get();
    }

    @Override
    public InferenceResult infer(InferenceRequest request, TokenSink sink) {
        String prompt = request.prompt();
        calls.computeIfAbsent(prompt, key -> new AtomicInteger()).incrementAndGet();

        CountDownLatch gate = gates.get(prompt);
        if (gate != null) {
            try {
                gate.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InferenceException("interrumpido", e, true);
            }
        }

        AtomicInteger remaining = failuresLeft.get(prompt);
        if (remaining != null && remaining.getAndDecrement() > 0) {
            throw new InferenceException("fallo programado para '" + prompt + "'", true);
        }
        if (prompt.startsWith("boom:")) {
            throw new InferenceException("error no reintentable", false);
        }

        sink.emit("resultado de ");
        sink.emit(prompt);
        return new InferenceResult("resultado de " + prompt, prompt.length());
    }

    @Override
    public String name() {
        return "programmable";
    }
}
