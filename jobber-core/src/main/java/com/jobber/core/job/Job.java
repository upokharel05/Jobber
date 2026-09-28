package com.jobber.core.job;

import java.time.Instant;

/**
 * A snapshot of one row in the {@code jobs} table.
 *
 * Immutable on purpose: state changes are not made by editing this object and saving it back,
 * but by explicit conditional UPDATE statements, so a stale copy can never overwrite a newer status.
 *
 * @param payload raw JSON text, stored as JSONB; the core module does not interpret it
 */
public record Job(
        Long id,
        String type,
        String payload,
        JobPriority priority,
        JobStatus status,
        Instant runAt,
        int attemptCount,
        int maxAttempts,
        String lastError,
        String idempotencyKey,
        String workerId,
        Instant leaseExpiresAt,
        Instant createdAt,
        Instant updatedAt,
        Instant startedAt,
        Instant finishedAt
) {
}
