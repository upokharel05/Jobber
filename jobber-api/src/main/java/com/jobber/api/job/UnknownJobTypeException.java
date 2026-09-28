package com.jobber.api.job;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** Rendered by Spring MVC as a 400 ProblemDetail that also lists the supported types. */
public class UnknownJobTypeException extends ErrorResponseException {

    public UnknownJobTypeException(String type, String[] supportedTypes) {
        super(HttpStatus.BAD_REQUEST, problem(type, supportedTypes), null);
    }

    private static ProblemDetail problem(String type, String[] supportedTypes) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Unknown job type '" + type + "'");
        problem.setTitle("Unknown job type");
        problem.setProperty("supportedTypes", List.of(supportedTypes));
        return problem;
    }
}
