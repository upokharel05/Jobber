package com.jobber.api.recovery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Runs the recovery checks periodically. Switch off with {@code jobber.recovery.enabled=false},
 * e.g. in tests that call {@link JobRecovery} directly.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "jobber.recovery.enabled", havingValue = "true", matchIfMissing = true)
class RecoveryScheduling {

    private static final Logger log = LoggerFactory.getLogger(RecoveryScheduling.class);

    private final JobRecovery recovery;

    RecoveryScheduling(JobRecovery recovery) {
        this.recovery = recovery;
    }

    @Scheduled(fixedDelayString = "${jobber.recovery.lease-check-interval:5s}")
    void recoverExpiredLeases() {
        try {
            recovery.recoverExpiredLeases();
        } catch (RuntimeException e) {
            log.warn("Lease recovery run failed; will retry: {}", e.toString());
        }
    }

    @Scheduled(fixedDelayString = "${jobber.recovery.stuck-queued-check-interval:1m}")
    void requeueStuckQueuedJobs() {
        try {
            recovery.requeueStuckQueuedJobs();
        } catch (RuntimeException e) {
            log.warn("Stuck-QUEUED check failed; will retry: {}", e.toString());
        }
    }
}
