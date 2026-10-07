package com.jobber.core.job;

/**
 * Proof of ownership for one execution of a job: which worker claimed it, on which attempt.
 *
 * Every update that ends an execution must match all three fields. {@code attempt} is the fencing
 * token: it increases with every claim, so a stalled holder of an older attempt (even on the same
 * worker) can no longer change the job once it has been recovered and claimed again.
 */
public record JobLease(long jobId, String workerId, int attempt) {

    /** The lease held by whoever just claimed this job. */
    public static JobLease of(Job job) {
        return new JobLease(job.id(), job.workerId(), job.attemptCount());
    }
}
