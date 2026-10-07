package com.jobber.api.recovery;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.jobber.api.TestcontainersConfiguration;
import com.jobber.core.job.Job;
import com.jobber.core.job.JobLease;
import com.jobber.core.job.JobPriority;
import com.jobber.core.job.JobRepository;
import com.jobber.core.job.JobStatus;
import com.jobber.core.job.JobType;
import com.jobber.core.job.NewJob;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class JobRecoveryTest {

    @Autowired
    JobRecovery recovery;

    @Autowired
    JobRepository jobs;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void startClean() {
        jdbc.sql("DELETE FROM jobs").update();
    }

    // --- expired leases (dead workers) ----------------------------------------------------------

    @Test
    void expiredLeaseWithAttemptsLeftIsRescheduledWithBackoff() {
        long id = running("worker-A", 1, 3, "-1 second");

        assertThat(recovery.recoverExpiredLeases()).isEqualTo(1);

        Job job = job(id);
        assertThat(job.status()).isEqualTo(JobStatus.SCHEDULED);
        assertThat(job.attemptCount()).isEqualTo(1);  // the crashed execution counted as an attempt
        assertThat(job.runAt()).isAfter(Instant.now().plusSeconds(4));  // attempt 1 backoff: 5-10s
        assertThat(job.lastError()).isEqualTo("Lease expired: worker worker-A presumed dead during attempt 1");
        assertThat(job.workerId()).isNull();
        assertThat(job.leaseExpiresAt()).isNull();
    }

    @Test
    void expiredLeaseOnLastAttemptFails() {
        long id = running("worker-A", 3, 3, "-1 second");

        recovery.recoverExpiredLeases();

        Job job = job(id);
        assertThat(job.status()).isEqualTo(JobStatus.FAILED);
        assertThat(job.lastError()).contains("presumed dead during attempt 3");
        assertThat(job.finishedAt()).isNotNull();
    }

    @Test
    void liveLeaseIsLeftAlone() {
        long id = running("worker-A", 1, 3, "+20 seconds");

        assertThat(recovery.recoverExpiredLeases()).isZero();
        assertThat(job(id).status()).isEqualTo(JobStatus.RUNNING);
    }

    @Test
    void concurrentRecoveryRunsRecoverEachJobExactlyOnce() throws Exception {
        for (int i = 0; i < 300; i++) {
            running("worker-A", 1, 3, "-1 second");
        }

        // Two recovery runs at once, as on two API instances: SKIP LOCKED splits the work.
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<Integer> a = pool.submit(() -> { start.await(); return recovery.recoverExpiredLeases(); });
            Future<Integer> b = pool.submit(() -> { start.await(); return recovery.recoverExpiredLeases(); });
            start.countDown();
            assertThat(a.get() + b.get()).isEqualTo(300);
        }
        assertThat(count("status = 'SCHEDULED'")).isEqualTo(300);
    }

    // --- fencing (zombie workers) ---------------------------------------------------------------

    @Test
    void zombieWorkerCannotCompleteJobAfterRecovery() {
        long id = running("worker-A", 1, 3, "-1 second");
        JobLease zombieLease = new JobLease(id, "worker-A", 1);

        recovery.recoverExpiredLeases();

        assertThat(jobs.markSucceeded(zombieLease)).isFalse();  // worker A wakes up and tries to finish
        assertThat(job(id).status()).isEqualTo(JobStatus.SCHEDULED);
    }

    @Test
    void staleAttemptOnTheSameWorkerIsFencedOff() {
        // Worker A's attempt 1 stalled, was recovered, and A itself re-claimed the job as attempt 2.
        // worker_id alone matches both; only the attempt number tells them apart.
        long id = running("worker-A", 2, 3, "+20 seconds");

        assertThat(jobs.markSucceeded(new JobLease(id, "worker-A", 1))).isFalse();
        assertThat(job(id).status()).isEqualTo(JobStatus.RUNNING);

        assertThat(jobs.markSucceeded(new JobLease(id, "worker-A", 2))).isTrue();
        assertThat(job(id).status()).isEqualTo(JobStatus.SUCCEEDED);
    }

    // --- stuck QUEUED jobs (lost messages) ------------------------------------------------------

    @Test
    void jobsStuckInQueuedAreSentBackForDispatch() {
        long stuck = queued("11 minutes");
        long fresh = queued("1 minute");

        assertThat(recovery.requeueStuckQueuedJobs()).isEqualTo(1);

        assertThat(job(stuck).status()).isEqualTo(JobStatus.SCHEDULED);
        assertThat(job(stuck).runAt()).isBeforeOrEqualTo(Instant.now());  // due immediately
        assertThat(job(fresh).status()).isEqualTo(JobStatus.QUEUED);
    }

    // --- helpers --------------------------------------------------------------------------------

    /** A RUNNING job on {@code attempt}, with its lease expiring at now() + {@code leaseOffset}. */
    private long running(String workerId, int attempt, int maxAttempts, String leaseOffset) {
        long id = insert(maxAttempts);
        jdbc.sql("""
                        UPDATE jobs SET status = 'RUNNING', worker_id = ?, attempt_count = ?,
                            started_at = now(), lease_expires_at = now() + CAST(? AS interval)
                        WHERE id = ?""")
                .params(workerId, attempt, leaseOffset, id)
                .update();
        return id;
    }

    /** A QUEUED job last updated {@code age} ago. */
    private long queued(String age) {
        long id = insert(3);
        jdbc.sql("UPDATE jobs SET status = 'QUEUED', updated_at = now() - CAST(? AS interval) WHERE id = ?")
                .params(age, id)
                .update();
        return id;
    }

    private long insert(int maxAttempts) {
        return jobs.insertIfAbsent(new NewJob(JobType.EMAIL_SEND, "{}", JobPriority.NORMAL, Instant.now(), maxAttempts, null))
                .orElseThrow()
                .id();
    }

    private Job job(long id) {
        return jobs.findById(id).orElseThrow();
    }

    private long count(String where) {
        return jdbc.sql("SELECT count(*) FROM jobs WHERE " + where).query(Long.class).single();
    }
}
