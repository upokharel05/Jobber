package com.jobber.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.random.RandomGenerator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RetryPolicyTest {

    static final Duration BASE = Duration.ofSeconds(10);
    static final Duration CAP = Duration.ofMinutes(10);

    @ParameterizedTest(name = "attempt {0}: {1}-{2} ms")
    @CsvSource({
            "1,      5000,  10000",
            "2,     10000,  20000",
            "3,     20000,  40000",
            "6,    160000, 320000",
            "7,    300000, 600000",  // 640s exceeds the 600s cap
            "20,   300000, 600000",
            "1000, 300000, 600000",  // no overflow for absurd attempt numbers
    })
    void delayDoublesPerAttemptWithinJitterRangeAndCap(int attempt, long minMillis, long maxMillis) {
        assertThat(policy(lowest()).delayAfterAttempt(attempt)).isEqualTo(Duration.ofMillis(minMillis));
        assertThat(policy(highest()).delayAfterAttempt(attempt)).isEqualTo(Duration.ofMillis(maxMillis));
    }

    @Test
    void realRandomnessStaysInRange() {
        RetryPolicy policy = new RetryPolicy(BASE, CAP);
        for (int i = 0; i < 1_000; i++) {
            assertThat(policy.delayAfterAttempt(2)).isBetween(Duration.ofSeconds(10), Duration.ofSeconds(20));
        }
    }

    private static RetryPolicy policy(RandomGenerator random) {
        return new RetryPolicy(BASE, CAP, random);
    }

    /** Always picks the bottom of the jitter range. */
    private static RandomGenerator lowest() {
        return new RandomGenerator() {
            @Override public long nextLong() { return 0; }
            @Override public long nextLong(long bound) { return 0; }
        };
    }

    /** Always picks the top of the jitter range. */
    private static RandomGenerator highest() {
        return new RandomGenerator() {
            @Override public long nextLong() { return 0; }
            @Override public long nextLong(long bound) { return bound - 1; }
        };
    }
}
