package com.jobber.core.messaging;

/**
 * The message sent to workers: just a pointer to the job. Workers read the job itself from the
 * database, so a message can never carry stale or conflicting job data.
 */
public record JobMessage(long jobId) {
}
