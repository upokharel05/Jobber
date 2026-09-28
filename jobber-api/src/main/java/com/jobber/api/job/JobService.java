package com.jobber.api.job;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

import org.springframework.stereotype.Service;

import com.jobber.core.job.Job;
import com.jobber.core.job.JobPriority;
import com.jobber.core.job.JobRepository;
import com.jobber.core.job.JobType;
import com.jobber.core.job.NewJob;

@Service
public class JobService {

    static final int DEFAULT_MAX_ATTEMPTS = 3;

    private final JobRepository jobs;

    public JobService(JobRepository jobs) {
        this.jobs = jobs;
    }

    /** @param created false when an existing job was returned for a reused idempotency key */
    public record SubmitResult(Job job, boolean created) {
    }

    public SubmitResult submit(SubmitJobRequest request, String idempotencyKey) {
        JobType type = JobType.fromTypeName(request.type())
                .orElseThrow(() -> new UnknownJobTypeException(request.type(), supportedTypeNames()));

        NewJob newJob = new NewJob(
                type,
                request.payload() == null || request.payload().isNull() ? "{}" : request.payload().toString(),
                Objects.requireNonNullElse(request.priority(), JobPriority.NORMAL),
                Objects.requireNonNullElseGet(request.runAt(), Instant::now),
                Objects.requireNonNullElse(request.maxAttempts(), DEFAULT_MAX_ATTEMPTS),
                idempotencyKey);

        return jobs.insertIfAbsent(newJob)
                .map(job -> new SubmitResult(job, true))
                // Insert skipped: the key is taken, so return the job that already owns it.
                // ON CONFLICT waits for a concurrent inserter to commit, so the row is visible here.
                .orElseGet(() -> new SubmitResult(jobs.findByIdempotencyKey(idempotencyKey).orElseThrow(), false));
    }

    public Job get(long id) {
        return jobs.findById(id).orElseThrow(() -> new JobNotFoundException(id));
    }

    private static String[] supportedTypeNames() {
        return Arrays.stream(JobType.values()).map(JobType::typeName).toArray(String[]::new);
    }
}
