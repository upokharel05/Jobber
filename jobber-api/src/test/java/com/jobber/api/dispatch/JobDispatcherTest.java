package com.jobber.api.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.jobber.api.TestcontainersConfiguration;
import com.jobber.core.job.JobPriority;
import com.jobber.core.job.JobRepository;
import com.jobber.core.job.JobStatus;
import com.jobber.core.job.JobType;
import com.jobber.core.job.NewJob;
import com.jobber.core.messaging.JobMessage;
import com.jobber.core.messaging.JobQueueConfig;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class JobDispatcherTest {

    @Autowired
    JobDispatcher dispatcher;

    @Autowired
    JobRepository jobs;

    @Autowired
    RabbitTemplate rabbit;

    @Autowired
    AmqpAdmin amqpAdmin;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void startClean() {
        // Other test classes share this database and broker; start from an empty slate.
        jdbc.sql("DELETE FROM jobs").update();
        amqpAdmin.purgeQueue(JobQueueConfig.QUEUE, false);
    }

    @Test
    void dispatchesOnlyDueJobsAndMarksThemQueued() {
        long due = insert(Instant.now().minusSeconds(1));
        long future = insert(Instant.now().plus(Duration.ofHours(1)));

        int dispatched = dispatcher.dispatchDueJobs();

        assertThat(dispatched).isEqualTo(1);
        assertThat(status(due)).isEqualTo(JobStatus.QUEUED);
        assertThat(status(future)).isEqualTo(JobStatus.SCHEDULED);
        assertThat(drainQueue()).containsExactly(due);
    }

    @Test
    void drainsMoreThanOneBatch() {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 250; i++) {  // batch size is 100
            ids.add(insert(Instant.now().minusSeconds(1)));
        }

        assertThat(dispatcher.dispatchDueJobs()).isEqualTo(250);
        assertThat(drainQueue()).containsExactlyInAnyOrderElementsOf(ids);
    }

    @Test
    void secondRunDispatchesNothingNew() {
        insert(Instant.now().minusSeconds(1));

        assertThat(dispatcher.dispatchDueJobs()).isEqualTo(1);
        assertThat(dispatcher.dispatchDueJobs()).isZero();  // already QUEUED, not re-published
        assertThat(drainQueue()).hasSize(1);
    }

    private long insert(Instant runAt) {
        return jobs.insertIfAbsent(new NewJob(JobType.EMAIL_SEND, "{}", JobPriority.NORMAL, runAt, 3, null))
                .orElseThrow()
                .id();
    }

    private JobStatus status(long id) {
        return jobs.findById(id).orElseThrow().status();
    }

    private List<Long> drainQueue() {
        List<Long> ids = new ArrayList<>();
        Object message;
        while ((message = rabbit.receiveAndConvert(JobQueueConfig.QUEUE, 500)) != null) {
            ids.add(((JobMessage) message).jobId());
        }
        return ids;
    }
}
