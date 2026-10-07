package com.jobber.worker;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.jobber.core.job.Job;
import com.jobber.core.job.JobLease;
import com.jobber.core.job.JobRepository;
import com.jobber.core.job.RetryPolicy;
import com.jobber.core.messaging.JobMessage;
import com.jobber.core.messaging.JobQueueConfig;
import com.jobber.worker.handler.JobHandlerRegistry;
import com.jobber.worker.handler.PermanentJobFailureException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumes job messages: claim (QUEUED -> RUNNING), run the handler, record the outcome.
 *
 * Delivery is at-least-once, so the same job may arrive more than once. The claim is a conditional
 * update in the database, which makes duplicates harmless: only one delivery can claim the job.
 *
 * Two kinds of failure are kept apart:
 * - A job failure (the handler threw) is recorded in Postgres as a retry or FAILED, and the message
 *   is acknowledged: the message did its job.
 * - A message failure (unreadable message, unknown job, outcome could not be recorded) throws out of
 *   this listener, so RabbitMQ dead-letters the message for inspection.
 */
@Component
public class JobExecutor {

    private static final Logger log = LoggerFactory.getLogger(JobExecutor.class);
    private static final int MAX_ERROR_LENGTH = 1000;

    private final JobRepository jobs;
    private final JobHandlerRegistry handlers;
    private final RetryPolicy retryPolicy;
    private final JsonMapper jsonMapper;
    private final String workerId;
    private final Duration lease;

    public JobExecutor(JobRepository jobs,
                       JobHandlerRegistry handlers,
                       RetryPolicy retryPolicy,
                       JsonMapper jsonMapper,
                       WorkerIdentity identity,
                       @Value("${jobber.worker.lease-duration:30s}") Duration lease) {
        this.jobs = jobs;
        this.handlers = handlers;
        this.retryPolicy = retryPolicy;
        this.jsonMapper = jsonMapper;
        this.workerId = identity.id();
        this.lease = lease;
    }

    @RabbitListener(queues = JobQueueConfig.QUEUE)
    public void onMessage(JobMessage message) {
        long jobId = message.jobId();

        Job job = jobs.claim(jobId, workerId, lease).orElse(null);
        if (job == null) {
            if (jobs.findById(jobId).isEmpty()) {
                throw new AmqpRejectAndDontRequeueException("Job " + jobId + " does not exist");
            }
            log.info("Job {} not claimable (duplicate delivery, or no longer QUEUED); skipping", jobId);
            return;
        }

        log.info("Running job {} type={} attempt={}/{}", job.id(), job.type(), job.attemptCount(), job.maxAttempts());
        try {
            handlers.handlerFor(job.type()).handle(job, parsePayload(job));
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            recordFailure(job, e);
            return;
        }

        if (jobs.markSucceeded(JobLease.of(job))) {
            log.info("Job {} succeeded", jobId);
        } else {
            log.warn("Job {} attempt {} finished, but its lease was lost (job recovered elsewhere); result discarded",
                    jobId, job.attemptCount());
        }
    }

    private void recordFailure(Job job, Exception e) {
        String error = describe(e);
        boolean permanent = e instanceof PermanentJobFailureException;
        boolean attemptsLeft = job.attemptCount() < job.maxAttempts();

        boolean recorded;
        if (!permanent && attemptsLeft) {
            Duration delay = retryPolicy.delayAfterAttempt(job.attemptCount());
            recorded = jobs.scheduleRetry(JobLease.of(job), delay, error);
            log.warn("Job {} attempt {}/{} failed, retrying in {} ms: {}",
                    job.id(), job.attemptCount(), job.maxAttempts(), delay.toMillis(), error);
        } else {
            recorded = jobs.markFailed(JobLease.of(job), error);
            log.error("Job {} failed permanently on attempt {}/{} ({}): {}", job.id(), job.attemptCount(),
                    job.maxAttempts(), permanent ? "non-retryable error" : "attempts exhausted", error);
        }
        if (!recorded) {
            log.warn("Job {} attempt {} failed, but its lease was lost (job recovered elsewhere); outcome discarded",
                    job.id(), job.attemptCount());
        }
    }

    private JsonNode parsePayload(Job job) {
        try {
            return jsonMapper.readTree(job.payload());
        } catch (JacksonException e) {
            throw new PermanentJobFailureException("Payload is not valid JSON", e);
        }
    }

    private static String describe(Exception e) {
        String text = e.getClass().getSimpleName() + ": " + e.getMessage();
        return text.length() <= MAX_ERROR_LENGTH ? text : text.substring(0, MAX_ERROR_LENGTH);
    }
}
