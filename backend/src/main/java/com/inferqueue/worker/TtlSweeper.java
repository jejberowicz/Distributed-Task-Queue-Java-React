package com.inferqueue.worker;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Barrido de TTL. El executor ya descarta jobs vencidos al tomarlos, pero un job
 * encolado detrás de una cola larga podría no ser tomado nunca; este sweeper lo
 * cierra igual para que el cliente vea EXPIRED y no un QUEUED eterno.
 */
@Component
@ConditionalOnProperty(name = "inferqueue.worker.enabled", havingValue = "true", matchIfMissing = true)
public class TtlSweeper {

    private final JobExecutor executor;

    public TtlSweeper(JobExecutor executor) {
        this.executor = executor;
    }

    @Scheduled(fixedDelayString = "PT10S")
    public void sweep() {
        executor.sweepExpired();
    }
}
