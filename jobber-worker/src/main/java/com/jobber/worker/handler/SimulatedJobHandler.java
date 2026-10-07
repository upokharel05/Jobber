package com.jobber.worker.handler;

import java.time.Duration;

import com.jobber.core.job.Job;
import com.jobber.core.job.JobType;

import tools.jackson.databind.JsonNode;

/**
 * Stand-in handler that pretends to do work, so the platform can be exercised before real
 * integrations exist. Behavior is controlled by an optional {@code "simulate"} object in the payload:
 *
 * <pre>
 * "simulate": {
 *   "durationMs": 500,          // how long the "work" takes (default: per job type)
 *   "failTimes": 2,             // fail temporarily on attempts 1..2, then succeed
 *   "permanentFailure": true    // fail permanently on the first attempt
 * }
 * </pre>
 */
public class SimulatedJobHandler implements JobHandler {

    private final JobType type;
    private final Duration defaultDuration;

    public SimulatedJobHandler(JobType type, Duration defaultDuration) {
        this.type = type;
        this.defaultDuration = defaultDuration;
    }

    @Override
    public JobType type() {
        return type;
    }

    @Override
    public void handle(Job job, JsonNode payload) throws InterruptedException {
        JsonNode simulate = payload.path("simulate");

        Thread.sleep(simulate.path("durationMs").asLong(defaultDuration.toMillis()));

        if (simulate.path("permanentFailure").asBoolean(false)) {
            throw new PermanentJobFailureException("Simulated permanent failure");
        }
        int failTimes = simulate.path("failTimes").asInt(0);
        if (job.attemptCount() <= failTimes) {
            throw new SimulatedTemporaryFailureException(
                    "Simulated temporary failure on attempt " + job.attemptCount() + " of " + failTimes + " planned");
        }
    }

    /** Stands in for a timeout, connection error, 503, etc. */
    public static class SimulatedTemporaryFailureException extends RuntimeException {
        public SimulatedTemporaryFailureException(String message) {
            super(message);
        }
    }
}
