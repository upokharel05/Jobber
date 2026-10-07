package com.jobber.worker;

import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Predicate;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import com.jobber.core.job.Job;
import com.jobber.core.job.JobPriority;
import com.jobber.core.job.JobRepository;
import com.jobber.core.job.JobStatus;
import com.jobber.core.job.JobType;
import com.jobber.core.job.NewJob;
import com.jobber.core.messaging.JobMessage;
import com.jobber.core.messaging.JobQueueConfig;

/** Shared setup for worker tests: a running worker against Testcontainers Postgres and RabbitMQ. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
abstract class WorkerIntegrationTest {

    @Autowired
    JobRepository jobs;

    @Autowired
    RabbitTemplate rabbit;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    WorkerIdentity identity;

    @Autowired
    TransactionTemplate transaction;

    long insertWithStatus(JobStatus status) {
        return insertWithStatus(status, "{}", 3);
    }

    long insertWithStatus(JobStatus status, String payload, int maxAttempts) {
        long id = jobs.insertIfAbsent(new NewJob(JobType.EMAIL_SEND, payload, JobPriority.NORMAL, Instant.now(), maxAttempts, null))
                .orElseThrow()
                .id();
        // Put the row directly into the state under test, bypassing the dispatcher.
        jdbc.sql("UPDATE jobs SET status = ? WHERE id = ?").params(status.name(), id).update();
        return id;
    }

    void publish(long id) {
        rabbit.convertAndSend(JobQueueConfig.EXCHANGE, JobQueueConfig.ROUTING_KEY, new JobMessage(id));
    }

    /** Plays the dispatcher's part for a retried job (the dispatcher lives in jobber-api), ignoring its backoff delay. */
    void redispatch(long id) {
        jdbc.sql("UPDATE jobs SET status = 'QUEUED' WHERE id = ? AND status = 'SCHEDULED'").param(id).update();
        publish(id);
    }

    Job awaitStatus(long id, JobStatus expected) {
        return awaitJob(id, job -> job.status() == expected);
    }

    Job awaitJob(long id, Predicate<Job> condition) {
        return await().atMost(Duration.ofSeconds(10))
                .until(() -> jobs.findById(id).orElseThrow(), condition::test);
    }

    static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
