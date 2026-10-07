package com.jobber.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.jobber.core.job.Job;
import com.jobber.core.job.JobStatus;

/** Heartbeats keep a running job's lease alive; a worker that lost its lease must not record a result. */
class LeaseTest extends WorkerIntegrationTest {

    @Test
    void heartbeatExtendsLeaseWhileJobRuns() {
        long id = insertWithStatus(JobStatus.QUEUED, """
                {"simulate": {"durationMs": 2500}}""", 3);
        publish(id);

        Instant firstLease = awaitStatus(id, JobStatus.RUNNING).leaseExpiresAt();
        // Test config heartbeats every 300ms; give it a few beats.
        Instant laterLease = awaitJob(id, j -> j.leaseExpiresAt() != null && j.leaseExpiresAt().isAfter(firstLease))
                .leaseExpiresAt();

        assertThat(laterLease).isAfter(firstLease);
        awaitStatus(id, JobStatus.SUCCEEDED);
    }

    @Test
    void resultIsDiscardedIfLeaseWasLostWhileRunning() {
        long id = insertWithStatus(JobStatus.QUEUED, """
                {"simulate": {"durationMs": 1500}}""", 3);
        publish(id);
        awaitStatus(id, JobStatus.RUNNING);

        // While the handler runs, lease recovery decides this worker is dead and reschedules the job.
        jdbc.sql("UPDATE jobs SET status = 'SCHEDULED', worker_id = NULL, lease_expires_at = NULL WHERE id = ?")
                .param(id).update();

        sleep(Duration.ofMillis(2500));  // the handler finishes and tries to mark the job SUCCEEDED
        Job job = jobs.findById(id).orElseThrow();
        assertThat(job.status()).isEqualTo(JobStatus.SCHEDULED);
        assertThat(job.finishedAt()).isNull();
    }
}
