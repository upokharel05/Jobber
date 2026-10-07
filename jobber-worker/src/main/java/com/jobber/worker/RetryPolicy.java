package com.jobber.worker;

import java.time.Duration;
import java.util.random.RandomGenerator;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Exponential backoff with jitter: the delay doubles per attempt up to a cap, then is randomized
 * between 50% and 100% of that value ("equal jitter"). The randomness spreads out jobs that failed
 * together, e.g. during a downstream outage, so their retries don't all land at the same moment.
 */
@Component
public class RetryPolicy {

    private final Duration baseDelay;
    private final Duration maxDelay;
    private final RandomGenerator random;

    @Autowired
    public RetryPolicy(@Value("${jobber.worker.retry.base-delay:10s}") Duration baseDelay,
                       @Value("${jobber.worker.retry.max-delay:10m}") Duration maxDelay) {
        this(baseDelay, maxDelay, RandomGenerator.getDefault());
    }

    RetryPolicy(Duration baseDelay, Duration maxDelay, RandomGenerator random) {
        this.baseDelay = baseDelay;
        this.maxDelay = maxDelay;
        this.random = random;
    }

    /** @param attempt the attempt that just failed, starting at 1 */
    public Duration delayAfterAttempt(int attempt) {
        int doublings = Math.min(Math.max(attempt - 1, 0), 30);  // bounded to avoid overflow
        long exponentialMillis = baseDelay.toMillis() << doublings;
        long cappedMillis = Math.min(maxDelay.toMillis(), exponentialMillis);
        long half = cappedMillis / 2;
        return Duration.ofMillis(half + random.nextLong(half + 1));
    }
}
