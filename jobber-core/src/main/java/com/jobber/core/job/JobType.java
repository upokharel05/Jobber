package com.jobber.core.job;

import java.util.Arrays;
import java.util.Optional;

/**
 * The job types Jobber knows how to run. The API rejects any other type, and the worker
 * provides a handler for each of these.
 */
public enum JobType {

    EMAIL_SEND("email.send"),
    REPORT_GENERATE("report.generate"),
    DATA_PROCESS("data.process"),
    FILE_EXPORT("file.export");

    private final String typeName;

    JobType(String typeName) {
        this.typeName = typeName;
    }

    /** The name clients use in requests and that is stored in the {@code jobs.type} column. */
    public String typeName() {
        return typeName;
    }

    public static Optional<JobType> fromTypeName(String typeName) {
        return Arrays.stream(values())
                .filter(t -> t.typeName.equals(typeName))
                .findFirst();
    }
}
