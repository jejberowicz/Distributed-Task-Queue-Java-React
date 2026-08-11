package com.inferqueue.worker;

import com.inferqueue.domain.Job;
import com.inferqueue.domain.JobEvent;
import com.inferqueue.domain.JobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Transiciones de estado del job en transacciones cortas. Vive aparte del
 * executor a propósito: la llamada al modelo puede tardar minutos y no queremos
 * una transacción abierta mientras tanto.
 *
 * <p>Todas las transiciones toman el job con {@code SELECT ... FOR UPDATE} y
 * abortan si ya está en estado terminal. Esa es la regla que hace segura la
 * cancelación: un worker que termina su inference después de que el cliente
 * canceló no puede pisar el CANCELED con un DONE.
 */
@Service
public class JobStateService {

    private static final Logger log = LoggerFactory.getLogger(JobStateService.class);

    private final JobRepository jobs;
    private final ApplicationEventPublisher events;

    public JobStateService(JobRepository jobs, ApplicationEventPublisher events) {
        this.jobs = jobs;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public Optional<Job> find(UUID jobId) {
        return jobs.findById(jobId);
    }

    /**
     * Toma el job para ejecutarlo. Devuelve vacío si mientras tanto alguien lo
     * cerró (cancelación o TTL), y en ese caso el executor no debe ejecutarlo.
     */
    @Transactional
    public Optional<Job> markProcessing(UUID jobId, String workerId, String streamMsgId) {
        return transition(jobId, job -> {
            job.markProcessing(workerId);
            job.setStreamMsgId(streamMsgId);
        });
    }

    @Transactional
    public Optional<Job> markDone(UUID jobId, String result, Integer tokensUsed) {
        return transition(jobId, job -> job.markDone(result, tokensUsed));
    }

    @Transactional
    public Optional<Job> markRetrying(UUID jobId, String error) {
        return transition(jobId, job -> job.markRetrying(error));
    }

    @Transactional
    public Optional<Job> markDead(UUID jobId, String error) {
        return transition(jobId, job -> job.markDead(error));
    }

    @Transactional
    public Optional<Job> markExpired(UUID jobId) {
        return transition(jobId, Job::markExpired);
    }

    @Transactional
    public Optional<Job> markCanceled(UUID jobId) {
        return transition(jobId, Job::markCanceled);
    }

    @Transactional(readOnly = true)
    public List<Job> findExpired(Instant now) {
        return jobs.findExpired(now);
    }

    private Optional<Job> transition(UUID jobId, java.util.function.Consumer<Job> mutation) {
        Optional<Job> locked = jobs.findByIdForUpdate(jobId);
        if (locked.isEmpty()) {
            return Optional.empty();
        }
        Job job = locked.get();
        if (job.getStatus().isTerminal()) {
            log.debug("Job {} ya estaba en {}, ignoro la transición", jobId, job.getStatus());
            return Optional.empty();
        }
        mutation.accept(job);
        Job saved = jobs.save(job);
        events.publishEvent(JobEvent.of(saved));
        return Optional.of(saved);
    }
}
