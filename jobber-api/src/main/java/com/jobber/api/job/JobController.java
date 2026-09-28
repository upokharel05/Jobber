package com.jobber.api.job;

import java.net.URI;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/jobs")
public class JobController {

    private final JobService jobService;

    public JobController(JobService jobService) {
        this.jobService = jobService;
    }

    /**
     * Submits a job. Returns 201 with a Location header for a new job, or 200 with the original job
     * when the {@code Idempotency-Key} was already used (the request body is not compared).
     */
    @PostMapping
    public ResponseEntity<JobResponse> submit(
            @Valid @RequestBody SubmitJobRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) @Size(min = 1, max = 255) String idempotencyKey) {

        JobService.SubmitResult result = jobService.submit(request, idempotencyKey);
        JobResponse body = JobResponse.from(result.job());

        if (!result.created()) {
            return ResponseEntity.ok(body);
        }
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(body.id())
                .toUri();
        return ResponseEntity.created(location).body(body);
    }

    @GetMapping("/{id}")
    public JobResponse get(@PathVariable long id) {
        return JobResponse.from(jobService.get(id));
    }
}
