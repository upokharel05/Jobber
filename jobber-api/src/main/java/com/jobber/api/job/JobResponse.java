package com.jobber.api.job;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonRawValue;
import com.jobber.core.job.Job;
import com.jobber.core.job.JobPriority;
import com.jobber.core.job.JobStatus;

/** What clients see for a job. Internal coordination fields (lease expiry) are left out. */
public record JobResponse(
        long id,
        String type,
        @JsonRawValue String payload,  // already JSON text; embed as-is rather than as a quoted string
        JobPriority priority,
        JobStatus status,
        Instant runAt,
        int attemptCount,
        int maxAttempts,
        String lastError,
        String idempotencyKey,
        String workerId,
        Instant createdAt,
        Instant updatedAt,
        Instant startedAt,
        Instant finishedAt
) {

    static JobResponse from(Job job) {
        return new JobResponse(
                job.id(),
                job.type(),
                job.payload(),
                job.priority(),
                job.status(),
                job.runAt(),
                job.attemptCount(),
                job.maxAttempts(),
                job.lastError(),
                job.idempotencyKey(),
                job.workerId(),
                job.createdAt(),
                job.updatedAt(),
                job.startedAt(),
                job.finishedAt());
    }
}
