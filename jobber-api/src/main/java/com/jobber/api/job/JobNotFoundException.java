package com.jobber.api.job;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** Rendered by Spring MVC as a 404 ProblemDetail. */
public class JobNotFoundException extends ErrorResponseException {

    public JobNotFoundException(long id) {
        super(HttpStatus.NOT_FOUND, problem(id), null);
    }

    private static ProblemDetail problem(long id) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Job " + id + " not found");
        problem.setTitle("Job not found");
        return problem;
    }
}
