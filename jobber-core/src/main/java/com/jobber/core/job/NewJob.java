package com.jobber.core.job;

import java.time.Instant;

/**
 * The data needed to create a job. Everything else (id, status, counters, timestamps) is set on insert.
 *
 * @param payload        raw JSON object text
 * @param idempotencyKey optional; {@code null} means no duplicate protection
 */
public record NewJob(
        JobType type,
        String payload,
        JobPriority priority,
        Instant runAt,
        int maxAttempts,
        String idempotencyKey
) {
}
