package com.jobber.api.dispatch;

import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.jobber.core.job.JobRepository;
import com.jobber.core.messaging.JobMessage;
import com.jobber.core.messaging.JobQueueConfig;

/**
 * Moves due jobs from the database onto the queue: SCHEDULED -> (publish) -> QUEUED.
 *
 * Order matters. We publish first and only mark QUEUED once the broker has confirmed the messages.
 * A crash in between leaves the job SCHEDULED, so it is published again on the next run. That
 * duplicate is harmless: workers claim with a conditional update, so only one delivery runs the job.
 * The opposite order could mark a job QUEUED without ever publishing it, losing it silently.
 */
@Component
public class JobDispatcher {

    private static final Logger log = LoggerFactory.getLogger(JobDispatcher.class);

    private final JobRepository jobs;
    private final RabbitTemplate rabbit;
    private final TransactionTemplate transaction;
    private final int batchSize;
    private final Duration confirmTimeout;

    public JobDispatcher(JobRepository jobs,
                         RabbitTemplate rabbit,
                         TransactionTemplate transaction,
                         @Value("${jobber.dispatcher.batch-size:100}") int batchSize,
                         @Value("${jobber.dispatcher.confirm-timeout:5s}") Duration confirmTimeout) {
        this.jobs = jobs;
        this.rabbit = rabbit;
        this.transaction = transaction;
        this.batchSize = batchSize;
        this.confirmTimeout = confirmTimeout;
    }

    /** Dispatches batches until no due jobs remain. @return the number of jobs dispatched */
    public int dispatchDueJobs() {
        int total = 0;
        int dispatched;
        do {
            dispatched = dispatchBatch();
            total += dispatched;
        } while (dispatched == batchSize);  // a full batch means more may be waiting
        if (total > 0) {
            log.info("Dispatched {} job(s)", total);
        }
        return total;
    }

    /**
     * One transaction: lock a batch of due rows, publish them, wait for broker confirms, then mark
     * them QUEUED. The row locks keep other dispatchers off these jobs until we commit.
     */
    private int dispatchBatch() {
        Integer count = transaction.execute(status -> {
            List<Long> ids = jobs.lockDueJobs(batchSize);
            if (ids.isEmpty()) {
                return 0;
            }
            rabbit.invoke(ops -> {
                for (Long id : ids) {
                    ops.convertAndSend(JobQueueConfig.EXCHANGE, JobQueueConfig.ROUTING_KEY, new JobMessage(id));
                }
                // Throws if the broker nacks or doesn't confirm in time -> transaction rolls back,
                // jobs stay SCHEDULED and are retried next run.
                ops.waitForConfirmsOrDie(confirmTimeout.toMillis());
                return null;
            });
            jobs.markQueued(ids);
            return ids.size();
        });
        return count == null ? 0 : count;
    }
}
