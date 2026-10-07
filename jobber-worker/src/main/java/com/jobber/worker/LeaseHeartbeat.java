package com.jobber.worker;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import com.jobber.core.job.JobRepository;

/**
 * Keeps this worker's leases alive while it runs jobs. If the process dies (or stalls long enough),
 * renewals stop, the leases expire, and the API's lease recovery takes the jobs back.
 *
 * Renewing every 10s against a 30s lease tolerates two missed heartbeats (a GC pause, a brief
 * database hiccup) before a live worker's jobs are considered abandoned.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class LeaseHeartbeat {

    private static final Logger log = LoggerFactory.getLogger(LeaseHeartbeat.class);

    private final JobRepository jobs;
    private final String workerId;
    private final Duration lease;

    LeaseHeartbeat(JobRepository jobs,
                   WorkerIdentity identity,
                   @Value("${jobber.worker.lease-duration:30s}") Duration lease) {
        this.jobs = jobs;
        this.workerId = identity.id();
        this.lease = lease;
    }

    @Scheduled(fixedDelayString = "${jobber.worker.heartbeat-interval:10s}")
    void renewLeases() {
        try {
            int renewed = jobs.renewLeases(workerId, lease);
            if (renewed > 0) {
                log.debug("Renewed {} lease(s)", renewed);
            }
        } catch (RuntimeException e) {
            log.warn("Lease renewal failed; will retry: {}", e.toString());
        }
    }
}
