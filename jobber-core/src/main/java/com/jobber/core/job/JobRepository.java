package com.jobber.core.job;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * All SQL for the {@code jobs} table. Shared by the API and the worker so both use exactly the
 * same statements, which is what makes the conditional-update guarantees hold.
 */
@Repository
public class JobRepository {

    private static final RowMapper<Job> JOB_ROW_MAPPER = JobRepository::mapJob;

    private final JdbcClient jdbc;

    public JobRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts a new job in {@link JobStatus#SCHEDULED} unless a job with the same idempotency key
     * already exists. The existence check and the insert are one atomic statement, so concurrent
     * submissions with the same key cannot both create a job.
     *
     * @return the inserted job, or empty if the idempotency key was already taken
     */
    public Optional<Job> insertIfAbsent(NewJob job) {
        return jdbc.sql("""
                        INSERT INTO jobs (type, payload, priority, status, run_at, max_attempts, idempotency_key)
                        VALUES (:type, CAST(:payload AS jsonb), :priority, 'SCHEDULED', :runAt, :maxAttempts, :idempotencyKey)
                        ON CONFLICT (idempotency_key) DO NOTHING
                        RETURNING *
                        """)
                .param("type", job.type().typeName())
                .param("payload", job.payload())
                .param("priority", job.priority().value())
                .param("runAt", OffsetDateTime.ofInstant(job.runAt(), ZoneOffset.UTC))
                .param("maxAttempts", job.maxAttempts())
                .param("idempotencyKey", job.idempotencyKey())
                .query(JOB_ROW_MAPPER)
                .optional();
    }

    /**
     * Locks up to {@code limit} due SCHEDULED jobs for dispatch. Must run inside a transaction; the
     * row locks are held until it ends. SKIP LOCKED makes concurrent dispatchers take disjoint
     * batches instead of waiting on (or double-publishing) each other's rows.
     */
    public List<Long> lockDueJobs(int limit) {
        return jdbc.sql("""
                        SELECT id FROM jobs
                        WHERE status = 'SCHEDULED' AND run_at <= now()
                        ORDER BY run_at
                        LIMIT :limit
                        FOR UPDATE SKIP LOCKED
                        """)
                .param("limit", limit)
                .query(Long.class)
                .list();
    }

    /** SCHEDULED -> QUEUED, for jobs whose messages the broker has confirmed. */
    public int markQueued(List<Long> ids) {
        JobStatus.SCHEDULED.requireTransitionTo(JobStatus.QUEUED);
        if (ids.isEmpty()) {
            return 0;
        }
        return jdbc.sql("""
                        UPDATE jobs SET status = 'QUEUED', updated_at = now()
                        WHERE id IN (:ids) AND status = 'SCHEDULED'
                        """)
                .param("ids", ids)
                .update();
    }

    /**
     * QUEUED -> RUNNING for one worker. The WHERE clause makes this a compare-and-set: if several
     * workers receive the same job (e.g. a duplicate message), exactly one gets the row back.
     *
     * The dispatcher publishes before it commits QUEUED, so a message can arrive while the row is
     * still SCHEDULED and locked by the dispatcher. A bare conditional UPDATE would see SCHEDULED,
     * skip the row without waiting, and strand the job. Locking the row first makes us wait for the
     * dispatcher's transaction to finish, so the UPDATE sees its outcome.
     *
     * @return the claimed job, or empty if it was not QUEUED (already claimed, finished, cancelled,
     *         or the dispatch rolled back, in which case the job will be published again)
     */
    @Transactional
    public Optional<Job> claim(long id, String workerId, Duration lease) {
        JobStatus.QUEUED.requireTransitionTo(JobStatus.RUNNING);
        jdbc.sql("SELECT id FROM jobs WHERE id = :id FOR UPDATE")
                .param("id", id)
                .query(Long.class)
                .optional();
        return jdbc.sql("""
                        UPDATE jobs
                        SET status = 'RUNNING',
                            worker_id = :workerId,
                            attempt_count = attempt_count + 1,
                            started_at = now(),
                            lease_expires_at = now() + make_interval(secs => :leaseSeconds),
                            updated_at = now()
                        WHERE id = :id AND status = 'QUEUED'
                        RETURNING *
                        """)
                .param("id", id)
                .param("workerId", workerId)
                .param("leaseSeconds", lease.toSeconds())
                .query(JOB_ROW_MAPPER)
                .optional();
    }

    /**
     * RUNNING -> SUCCEEDED. Only the worker holding the job may complete it.
     *
     * @return false if the job is no longer RUNNING under this worker
     */
    public boolean markSucceeded(long id, String workerId) {
        JobStatus.RUNNING.requireTransitionTo(JobStatus.SUCCEEDED);
        return jdbc.sql("""
                        UPDATE jobs
                        SET status = 'SUCCEEDED', finished_at = now(), lease_expires_at = NULL, updated_at = now()
                        WHERE id = :id AND status = 'RUNNING' AND worker_id = :workerId
                        """)
                .param("id", id)
                .param("workerId", workerId)
                .update() == 1;
    }

    /**
     * RUNNING -> SCHEDULED after a failed attempt that will be retried. The job becomes due again
     * after {@code delay}, so the dispatcher re-publishes it like any delayed job. The delay is
     * applied with the database clock, the same clock the dispatcher compares {@code run_at} against.
     *
     * @return false if the job is no longer RUNNING under this worker
     */
    public boolean scheduleRetry(long id, String workerId, Duration delay, String error) {
        JobStatus.RUNNING.requireTransitionTo(JobStatus.SCHEDULED);
        return jdbc.sql("""
                        UPDATE jobs
                        SET status = 'SCHEDULED',
                            run_at = now() + make_interval(secs => :delaySeconds),
                            last_error = :error,
                            worker_id = NULL,
                            lease_expires_at = NULL,
                            updated_at = now()
                        WHERE id = :id AND status = 'RUNNING' AND worker_id = :workerId
                        """)
                .param("id", id)
                .param("workerId", workerId)
                .param("delaySeconds", delay.toMillis() / 1000.0)
                .param("error", error)
                .update() == 1;
    }

    /**
     * RUNNING -> FAILED: retries exhausted or a permanent error. Terminal.
     *
     * @return false if the job is no longer RUNNING under this worker
     */
    public boolean markFailed(long id, String workerId, String error) {
        JobStatus.RUNNING.requireTransitionTo(JobStatus.FAILED);
        return jdbc.sql("""
                        UPDATE jobs
                        SET status = 'FAILED', last_error = :error, finished_at = now(), lease_expires_at = NULL, updated_at = now()
                        WHERE id = :id AND status = 'RUNNING' AND worker_id = :workerId
                        """)
                .param("id", id)
                .param("workerId", workerId)
                .param("error", error)
                .update() == 1;
    }

    public Optional<Job> findById(long id) {
        return jdbc.sql("SELECT * FROM jobs WHERE id = :id")
                .param("id", id)
                .query(JOB_ROW_MAPPER)
                .optional();
    }

    public Optional<Job> findByIdempotencyKey(String idempotencyKey) {
        return jdbc.sql("SELECT * FROM jobs WHERE idempotency_key = :key")
                .param("key", idempotencyKey)
                .query(JOB_ROW_MAPPER)
                .optional();
    }

    private static Job mapJob(ResultSet rs, int rowNum) throws SQLException {
        return new Job(
                rs.getLong("id"),
                rs.getString("type"),
                rs.getString("payload"),
                JobPriority.fromValue(rs.getInt("priority")),
                JobStatus.valueOf(rs.getString("status")),
                instant(rs, "run_at"),
                rs.getInt("attempt_count"),
                rs.getInt("max_attempts"),
                rs.getString("last_error"),
                rs.getString("idempotency_key"),
                rs.getString("worker_id"),
                instant(rs, "lease_expires_at"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"),
                instant(rs, "started_at"),
                instant(rs, "finished_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
