package com.jobber.worker.handler;

import com.jobber.core.job.Job;
import com.jobber.core.job.JobType;

import tools.jackson.databind.JsonNode;

/**
 * Executes one type of job. Returning normally means success.
 *
 * Throwing {@link PermanentJobFailureException} fails the job immediately. Any other exception is
 * treated as temporary and the job is retried while it has attempts left.
 */
public interface JobHandler {

    JobType type();

    void handle(Job job, JsonNode payload) throws Exception;
}
