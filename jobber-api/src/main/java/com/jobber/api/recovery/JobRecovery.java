package com.jobber.api.recovery;

import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.jobber.core.job.JobRepository;
import com.jobber.core.job.JobRepository.ExpiredLease;
import com.jobber.core.job.RetryPolicy;

/**
 * Finds jobs that got stuck because something died, and puts them back on track.
 *
 * Safe to run on several API instances at once without leader election: expired leases are locked
 * with SKIP LOCKED, and every change is a conditional update, so each stuck job is recovered once.
 */
@Component
public class JobRecovery {

    private static final Logger log = LoggerFactory.getLogger(JobRecovery.class);

    private final JobRepository jobs;
    private final RetryPolicy retryPolicy;
    private final TransactionTemplate transaction;
    private final int batchSize;
    private final Duration stuckQueuedThreshold;

    public JobRecovery(JobRepository jobs,
                       RetryPolicy retryPolicy,
                       TransactionTemplate transaction,
                       @Value("${jobber.recovery.batch-size:100}") int batchSize,
                       @Value("${jobber.recovery.stuck-queued-threshold:10m}") Duration stuckQueuedThreshold) {
        this.jobs = jobs;
        this.retryPolicy = retryPolicy;
        this.transaction = transaction;
        this.batchSize = batchSize;
        this.stuckQueuedThreshold = stuckQueuedThreshold;
    }

    /**
     * RUNNING jobs whose worker stopped renewing its lease: the worker is presumed dead. The crashed
     * execution counts as an attempt (attempt_count rose when it was claimed), so a job that keeps
     * killing workers still runs out of attempts. Retries use the normal backoff: if the job itself
     * caused the crash, retrying instantly would just take down the next worker.
     *
     * @return the number of jobs recovered
     */
    public int recoverExpiredLeases() {
        int total = 0;
        int recovered;
        do {
            recovered = recoverBatch();
            total += recovered;
        } while (recovered == batchSize);
        return total;
    }

    private int recoverBatch() {
        Integer count = transaction.execute(status -> {
            List<ExpiredLease> expired = jobs.lockExpiredLeases(batchSize);
            for (ExpiredLease job : expired) {
                var lease = job.lease();
                String error = "Lease expired: worker " + lease.workerId() + " presumed dead during attempt " + lease.attempt();
                if (lease.attempt() < job.maxAttempts()) {
                    Duration delay = retryPolicy.delayAfterAttempt(lease.attempt());
                    jobs.scheduleRetry(lease, delay, error);
                    log.warn("Job {}: {}; retrying in {} ms", lease.jobId(), error, delay.toMillis());
                } else {
                    jobs.markFailed(lease, error);
                    log.error("Job {}: {}; no attempts left, marked FAILED", lease.jobId(), error);
                }
            }
            return expired.size();
        });
        return count == null ? 0 : count;
    }

    /**
     * QUEUED jobs that have waited longer than the threshold, presumably because their message was
     * lost (e.g. dead-lettered when a worker couldn't reach the database). They go back to SCHEDULED
     * and the dispatcher publishes them again.
     *
     * @return the number of jobs sent back for dispatch
     */
    public int requeueStuckQueuedJobs() {
        int requeued = jobs.requeueStuckQueued(stuckQueuedThreshold);
        if (requeued > 0) {
            log.warn("Re-dispatching {} job(s) stuck in QUEUED for over {}", requeued, stuckQueuedThreshold);
        }
        return requeued;
    }
}
