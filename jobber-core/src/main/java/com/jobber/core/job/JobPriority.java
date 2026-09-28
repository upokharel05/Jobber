package com.jobber.core.job;

import java.util.Arrays;

/**
 * Job priority. Stored as a number so the database can sort by it (higher = more urgent).
 * Recorded now; scheduling behavior based on it is implemented later.
 */
public enum JobPriority {

    LOW(1),
    NORMAL(2),
    HIGH(3);

    private final int value;

    JobPriority(int value) {
        this.value = value;
    }

    public int value() {
        return value;
    }

    public static JobPriority fromValue(int value) {
        return Arrays.stream(values())
                .filter(p -> p.value == value)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown job priority: " + value));
    }
}
