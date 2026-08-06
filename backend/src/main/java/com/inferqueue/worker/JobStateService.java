package com.inferqueue.worker;

import com.inferqueue.domain.Job;
import com.inferqueue.domain.JobEvent;
import com.inferqueue.domain.JobRepository;
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
 */
@Service
public class JobStateService {

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

    @Transactional
    public Job markProcessing(UUID jobId, String workerId, String streamMsgId) {
        Job job = jobs.findById(jobId).orElseThrow();
        job.markProcessing(workerId);
        job.setStreamMsgId(streamMsgId);
        return publish(jobs.save(job));
    }

    @Transactional
    public Job markDone(UUID jobId, String result, Integer tokensUsed) {
        Job job = jobs.findById(jobId).orElseThrow();
        job.markDone(result, tokensUsed);
        return publish(jobs.save(job));
    }

    @Transactional
    public Job markRetrying(UUID jobId, String error) {
        Job job = jobs.findById(jobId).orElseThrow();
        job.markRetrying(error);
        return publish(jobs.save(job));
    }

    @Transactional
    public Job markDead(UUID jobId, String error) {
        Job job = jobs.findById(jobId).orElseThrow();
        job.markDead(error);
        return publish(jobs.save(job));
    }

    @Transactional
    public Job markExpired(UUID jobId) {
        Job job = jobs.findById(jobId).orElseThrow();
        job.markExpired();
        return publish(jobs.save(job));
    }

    @Transactional(readOnly = true)
    public List<Job> findExpired(Instant now) {
        return jobs.findExpired(now);
    }

    private Job publish(Job job) {
        events.publishEvent(JobEvent.of(job));
        return job;
    }
}
