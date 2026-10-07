package com.jobber.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.annotation.Autowired;

import com.jobber.core.job.Job;
import com.jobber.core.job.JobStatus;
import com.jobber.core.messaging.JobMessage;
import com.jobber.core.messaging.JobQueueConfig;

/**
 * Job failures end up in Postgres (retry or FAILED); message failures end up in the RabbitMQ
 * dead-letter queue. Payloads use the simulated handlers' "simulate" controls.
 */
class JobFailureHandlingTest extends WorkerIntegrationTest {

    @Autowired
    AmqpAdmin amqpAdmin;

    @BeforeEach
    void emptyDeadLetterQueue() {
        amqpAdmin.purgeQueue(JobQueueConfig.DEAD_LETTER_QUEUE, false);
    }

    // --- job failures: recorded in Postgres ---------------------------------------------------

    @Test
    void temporaryFailureSchedulesRetryWithBackoff() {
        long id = insertWithStatus(JobStatus.QUEUED, """
                {"simulate": {"failTimes": 1}}""", 3);
        Instant beforeRun = Instant.now();

        publish(id);

        Job job = awaitStatus(id, JobStatus.SCHEDULED);
        assertThat(job.attemptCount()).isEqualTo(1);
        assertThat(job.lastError()).contains("Simulated temporary failure on attempt 1");
        assertThat(job.workerId()).isNull();
        assertThat(job.leaseExpiresAt()).isNull();
        // Attempt 1 with a 10s base delay: due again in 5-10s (equal jitter).
        assertThat(job.runAt()).isBetween(beforeRun.plusSeconds(4), Instant.now().plusSeconds(11));
    }

    @Test
    void retriesUntilSuccess() {
        long id = insertWithStatus(JobStatus.QUEUED, """
                {"simulate": {"failTimes": 2}}""", 3);

        publish(id);
        awaitJob(id, j -> j.status() == JobStatus.SCHEDULED && j.attemptCount() == 1);
        redispatch(id);
        awaitJob(id, j -> j.status() == JobStatus.SCHEDULED && j.attemptCount() == 2);
        redispatch(id);

        Job job = awaitStatus(id, JobStatus.SUCCEEDED);
        assertThat(job.attemptCount()).isEqualTo(3);
        assertThat(job.lastError()).contains("attempt 2");  // kept as a record of what went wrong before
    }

    @Test
    void failsWhenAttemptsAreExhausted() {
        long id = insertWithStatus(JobStatus.QUEUED, """
                {"simulate": {"failTimes": 5}}""", 2);

        publish(id);
        awaitJob(id, j -> j.status() == JobStatus.SCHEDULED && j.attemptCount() == 1);
        redispatch(id);

        Job job = awaitStatus(id, JobStatus.FAILED);
        assertThat(job.attemptCount()).isEqualTo(2);
        assertThat(job.lastError()).contains("attempt 2");
        assertThat(job.finishedAt()).isNotNull();
    }

    @Test
    void singleAttemptJobFailsOnFirstTemporaryError() {
        long id = insertWithStatus(JobStatus.QUEUED, """
                {"simulate": {"failTimes": 1}}""", 1);

        publish(id);

        assertThat(awaitStatus(id, JobStatus.FAILED).attemptCount()).isEqualTo(1);
    }

    @Test
    void permanentFailureSkipsRemainingAttempts() {
        long id = insertWithStatus(JobStatus.QUEUED, """
                {"simulate": {"permanentFailure": true}}""", 3);

        publish(id);

        Job job = awaitStatus(id, JobStatus.FAILED);
        assertThat(job.attemptCount()).isEqualTo(1);
        assertThat(job.lastError()).startsWith("PermanentJobFailureException: Simulated permanent failure");
    }

    @Test
    void jobWithTypeUnknownToWorkerFailsPermanently() {
        long id = insertWithStatus(JobStatus.QUEUED);
        jdbc.sql("UPDATE jobs SET type = 'sms.send' WHERE id = ?").param(id).update();

        publish(id);

        Job job = awaitStatus(id, JobStatus.FAILED);
        assertThat(job.attemptCount()).isEqualTo(1);
        assertThat(job.lastError()).contains("Unknown job type 'sms.send'");
    }

    // --- message failures: dead-lettered in RabbitMQ ------------------------------------------

    @Test
    void messageForNonexistentJobIsDeadLettered() {
        publish(Long.MAX_VALUE);

        Object dead = rabbit.receiveAndConvert(JobQueueConfig.DEAD_LETTER_QUEUE, Duration.ofSeconds(10).toMillis());
        assertThat(dead).isEqualTo(new JobMessage(Long.MAX_VALUE));
    }

    @Test
    void unreadableMessageIsDeadLettered() {
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        rabbit.send(JobQueueConfig.EXCHANGE, JobQueueConfig.ROUTING_KEY,
                new Message("this is not json".getBytes(StandardCharsets.UTF_8), props));

        Message dead = rabbit.receive(JobQueueConfig.DEAD_LETTER_QUEUE, Duration.ofSeconds(10).toMillis());
        assertThat(dead).isNotNull();
        assertThat(new String(dead.getBody(), StandardCharsets.UTF_8)).isEqualTo("this is not json");
    }
}
