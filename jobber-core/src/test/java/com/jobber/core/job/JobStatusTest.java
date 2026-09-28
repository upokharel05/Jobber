package com.jobber.core.job;

import static com.jobber.core.job.JobStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

class JobStatusTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "SCHEDULED, QUEUED",
            "SCHEDULED, CANCELLED",
            "QUEUED,    RUNNING",
            "QUEUED,    CANCELLED",
            "RUNNING,   SUCCEEDED",
            "RUNNING,   SCHEDULED",  // retry, or reclaimed after a worker's lease expired
            "RUNNING,   FAILED",
    })
    void allowsLifecycleTransitions(JobStatus from, JobStatus to) {
        assertThat(from.canTransitionTo(to)).isTrue();
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "SCHEDULED, RUNNING",    // must go through the queue
            "SCHEDULED, SUCCEEDED",
            "QUEUED,    SUCCEEDED",  // must run first
            "RUNNING,   CANCELLED",  // cancelling running jobs is not supported (yet)
            "RUNNING,   QUEUED",
            "QUEUED,    QUEUED",     // self-transitions are rejected
    })
    void rejectsIllegalTransitions(JobStatus from, JobStatus to) {
        assertThat(from.canTransitionTo(to)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = JobStatus.class, names = {"SUCCEEDED", "FAILED", "CANCELLED"})
    void terminalStatesAllowNoTransitions(JobStatus status) {
        assertThat(status.isTerminal()).isTrue();
        assertThat(status.allowedTransitions()).isEmpty();
    }

    @Test
    void nonTerminalStates() {
        assertThat(SCHEDULED.isTerminal()).isFalse();
        assertThat(QUEUED.isTerminal()).isFalse();
        assertThat(RUNNING.isTerminal()).isFalse();
    }
}
