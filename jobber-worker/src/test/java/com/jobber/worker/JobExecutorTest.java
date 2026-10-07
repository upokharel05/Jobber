package com.jobber.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.jobber.core.job.Job;
import com.jobber.core.job.JobStatus;

/** The happy path and delivery edge cases (duplicates, early arrival, stale messages). */
class JobExecutorTest extends WorkerIntegrationTest {

    @Test
    void claimsRunsAndCompletesQueuedJob() {
        long id = insertWithStatus(JobStatus.QUEUED);

        publish(id);

        Job job = awaitStatus(id, JobStatus.SUCCEEDED);
        assertThat(job.attemptCount()).isEqualTo(1);
        assertThat(job.workerId()).isEqualTo(identity.id());
        assertThat(job.startedAt()).isNotNull();
        assertThat(job.finishedAt()).isAfterOrEqualTo(job.startedAt());
        assertThat(job.leaseExpiresAt()).isNull();
    }

    @Test
    void duplicateDeliveryRunsJobOnlyOnce() {
        long id = insertWithStatus(JobStatus.QUEUED);

        publish(id);
        publish(id);  // e.g. dispatcher crashed after publishing but before marking QUEUED

        awaitStatus(id, JobStatus.SUCCEEDED);
        // Let the second delivery be processed, then confirm it did not claim the job again.
        await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(3))
                .until(() -> jobs.findById(id).orElseThrow().attemptCount() == 1);
    }

    /**
     * Regression test for a race found in end-to-end testing: the dispatcher publishes before it
     * commits QUEUED, so the worker can receive the message while the row is still SCHEDULED.
     * The worker must wait for the dispatcher's transaction instead of skipping the job.
     */
    @Test
    void messageArrivingBeforeDispatcherCommitsIsStillProcessed() {
        long id = insertWithStatus(JobStatus.SCHEDULED);

        // Mimic JobDispatcher: lock the row, publish, and only then mark QUEUED and commit.
        transaction.executeWithoutResult(status -> {
            jdbc.sql("SELECT id FROM jobs WHERE id = ? FOR UPDATE").param(id).query(Long.class).single();
            publish(id);
            sleep(Duration.ofMillis(500));  // the worker receives the message during this window
            jdbc.sql("UPDATE jobs SET status = 'QUEUED' WHERE id = ?").param(id).update();
        });

        Job job = awaitStatus(id, JobStatus.SUCCEEDED);
        assertThat(job.attemptCount()).isEqualTo(1);
    }

    @Test
    void ignoresMessageForJobThatIsNotQueued() {
        long scheduled = insertWithStatus(JobStatus.SCHEDULED);
        long marker = insertWithStatus(JobStatus.QUEUED);

        publish(scheduled);
        publish(marker);  // prefetch=1 and one consumer: once this is done, the first was handled too

        awaitStatus(marker, JobStatus.SUCCEEDED);
        Job untouched = jobs.findById(scheduled).orElseThrow();
        assertThat(untouched.status()).isEqualTo(JobStatus.SCHEDULED);
        assertThat(untouched.attemptCount()).isZero();
    }
}
