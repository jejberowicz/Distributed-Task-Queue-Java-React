package com.inferqueue.worker;

import com.inferqueue.domain.Job;
import com.inferqueue.domain.JobType;

public record InferenceRequest(String model, JobType type, String prompt) {

    public static InferenceRequest from(Job job) {
        return new InferenceRequest(job.getModel(), job.getJobType(), job.getPrompt());
    }
}
