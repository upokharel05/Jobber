package com.jobber.worker.handler;

/** Thrown when retrying cannot help (bad input, rejected by a downstream service, ...). Fails the job without retries. */
public class PermanentJobFailureException extends RuntimeException {

    public PermanentJobFailureException(String message) {
        super(message);
    }

    public PermanentJobFailureException(String message, Throwable cause) {
        super(message, cause);
    }
}
