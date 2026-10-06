package com.jobber.api.dispatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Runs the dispatcher on a fixed delay. Kept separate from {@link JobDispatcher} so it can be
 * switched off ({@code jobber.dispatcher.enabled=false}), e.g. in tests that drive dispatch by hand.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "jobber.dispatcher.enabled", havingValue = "true", matchIfMissing = true)
class DispatcherScheduling {

    private static final Logger log = LoggerFactory.getLogger(DispatcherScheduling.class);

    private final JobDispatcher dispatcher;

    DispatcherScheduling(JobDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    // Fixed delay (not rate): the next run starts only after the previous one finishes.
    @Scheduled(fixedDelayString = "${jobber.dispatcher.poll-interval:500ms}")
    void dispatch() {
        try {
            dispatcher.dispatchDueJobs();
        } catch (RuntimeException e) {
            // Broker or database unavailable: log and try again next tick; nothing is lost.
            log.warn("Dispatch run failed; will retry: {}", e.toString());
        }
    }
}
