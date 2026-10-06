package com.jobber.worker;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.jobber.core.job.Job;
import com.jobber.core.job.JobRepository;
import com.jobber.core.messaging.JobMessage;
import com.jobber.core.messaging.JobQueueConfig;

/**
 * Consumes job messages: claim (QUEUED -> RUNNING), execute, complete (RUNNING -> SUCCEEDED).
 *
 * Delivery is at-least-once, so the same job may arrive more than once. The claim is a conditional
 * update in the database, which makes duplicates harmless: only one delivery can claim the job.
 */
@Component
public class JobExecutor {

    private static final Logger log = LoggerFactory.getLogger(JobExecutor.class);

    private final JobRepository jobs;
    private final String workerId;
    private final Duration lease;

    public JobExecutor(JobRepository jobs,
                       WorkerIdentity identity,
                       @Value("${jobber.worker.lease-duration:5m}") Duration lease) {
        this.jobs = jobs;
        this.workerId = identity.id();
        this.lease = lease;
    }

    @RabbitListener(queues = JobQueueConfig.QUEUE)
    public void onMessage(JobMessage message) {
        long jobId = message.jobId();

        Job job = jobs.claim(jobId, workerId, lease).orElse(null);
        if (job == null) {
            log.info("Job {} not claimable (duplicate delivery, or no longer QUEUED); skipping", jobId);
            return;
        }

        log.info("Running job {} type={} attempt={}/{} payload={}",
                job.id(), job.type(), job.attemptCount(), job.maxAttempts(), job.payload());
        // Real per-type handlers come in a later step.

        if (jobs.markSucceeded(jobId, workerId)) {
            log.info("Job {} succeeded", jobId);
        } else {
            log.warn("Job {} finished but was no longer ours to complete", jobId);
        }
    }
}
