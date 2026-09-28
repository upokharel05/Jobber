package com.jobber.api.job;

import java.time.Instant;

import com.jobber.core.job.JobPriority;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import tools.jackson.databind.JsonNode;

/**
 * Body of {@code POST /jobs}. Only {@code type} is required.
 *
 * @param payload     job input; must be a JSON object if present (defaults to {@code {}})
 * @param priority    defaults to {@link JobPriority#NORMAL}
 * @param runAt       earliest time to run; defaults to now (a time in the past also means "now")
 * @param maxAttempts total attempts including the first; defaults to 3
 */
public record SubmitJobRequest(
        @NotBlank String type,
        JsonNode payload,
        JobPriority priority,
        Instant runAt,
        @Min(1) @Max(10) Integer maxAttempts
) {

    @AssertTrue(message = "payload must be a JSON object")
    boolean isPayloadObject() {
        return payload == null || payload.isNull() || payload.isObject();
    }
}
