package com.jobber.api.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.jobber.api.TestcontainersConfiguration;

/**
 * Many clients retrying the same submission at the same instant must still produce exactly one job.
 * A "check, then insert" implementation fails this test; the atomic INSERT ... ON CONFLICT passes it.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ConcurrentIdempotencyTest {

    static final int CLIENTS = 20;

    @Autowired
    JobService jobService;

    @Autowired
    JdbcClient jdbc;

    @Test
    void concurrentSubmissionsWithSameKeyCreateExactlyOneJob() throws Exception {
        String key = UUID.randomUUID().toString();
        var request = new SubmitJobRequest("email.send", null, null, null, null);
        CountDownLatch startGate = new CountDownLatch(1);

        List<JobService.SubmitResult> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(CLIENTS)) {
            List<Future<JobService.SubmitResult>> futures = new ArrayList<>();
            for (int i = 0; i < CLIENTS; i++) {
                futures.add(pool.submit(() -> {
                    startGate.await();  // release all threads together to maximize contention
                    return jobService.submit(request, key);
                }));
            }
            startGate.countDown();
            for (Future<JobService.SubmitResult> f : futures) {
                results.add(f.get());
            }
        }

        assertThat(results).filteredOn(JobService.SubmitResult::created).hasSize(1);
        assertThat(results).extracting(r -> r.job().id()).containsOnly(results.getFirst().job().id());
        assertThat(jdbc.sql("SELECT count(*) FROM jobs WHERE idempotency_key = ?").param(key).query(Long.class).single())
                .isEqualTo(1);
    }
}
