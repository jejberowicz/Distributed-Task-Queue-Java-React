package com.inferqueue.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

@Component
public class QueueMetrics {

    private final Counter completed;
    private final Counter retried;
    private final Counter dead;
    private final Counter expired;
    private final Counter reclaimed;
    private final Timer inferenceTimer;
    private final LongAdder tokens = new LongAdder();

    public QueueMetrics(MeterRegistry registry) {
        this.completed = Counter.builder("inferqueue.jobs.completed").register(registry);
        this.retried = Counter.builder("inferqueue.jobs.retried").register(registry);
        this.dead = Counter.builder("inferqueue.jobs.dead").register(registry);
        this.expired = Counter.builder("inferqueue.jobs.expired").register(registry);
        this.reclaimed = Counter.builder("inferqueue.jobs.reclaimed")
                .description("Mensajes huérfanos recuperados con XCLAIM tras la muerte de un worker")
                .register(registry);
        this.inferenceTimer = Timer.builder("inferqueue.inference.duration").register(registry);
        registry.gauge("inferqueue.tokens.total", tokens, LongAdder::sum);
    }

    public void recordCompleted(long durationNanos, Integer tokensUsed) {
        completed.increment();
        inferenceTimer.record(durationNanos, TimeUnit.NANOSECONDS);
        if (tokensUsed != null) {
            tokens.add(tokensUsed);
        }
    }

    public void recordRetry() {
        retried.increment();
    }

    public void recordDead() {
        dead.increment();
    }

    public void recordExpired() {
        expired.increment();
    }

    public void recordReclaimed(int count) {
        reclaimed.increment(count);
    }
}
