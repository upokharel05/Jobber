package com.jobber.core.job;

import java.util.EnumSet;
import java.util.Set;

/**
 * Lifecycle states of a job and the transitions allowed between them.
 *
 * <pre>
 * SCHEDULED --(run_at reached)--> QUEUED --> RUNNING --> SUCCEEDED
 *     ^                                         |
 *     +---- attempt failed, retries left <------+
 *                                               +---- retries exhausted --> FAILED
 * SCHEDULED / QUEUED --(cancel)--> CANCELLED
 * </pre>
 *
 * This enum only decides whether a transition is legal. Guaranteeing that a single worker
 * wins a race for the same transition is the database's job (conditional UPDATE).
 */
public enum JobStatus {

    /** Waiting for {@code run_at}; also where retries and reclaimed jobs return to. */
    SCHEDULED,
    /** Due and handed to the queue, waiting for a worker. */
    QUEUED,
    /** Claimed by a worker and executing. */
    RUNNING,
    SUCCEEDED,
    /** Retries exhausted. */
    FAILED,
    CANCELLED;

    public Set<JobStatus> allowedTransitions() {
        return switch (this) {
            case SCHEDULED -> EnumSet.of(QUEUED, CANCELLED);
            case QUEUED -> EnumSet.of(RUNNING, CANCELLED);
            case RUNNING -> EnumSet.of(SUCCEEDED, SCHEDULED, FAILED);
            case SUCCEEDED, FAILED, CANCELLED -> EnumSet.noneOf(JobStatus.class);
        };
    }

    public boolean canTransitionTo(JobStatus target) {
        return allowedTransitions().contains(target);
    }

    public boolean isTerminal() {
        return allowedTransitions().isEmpty();
    }
}
