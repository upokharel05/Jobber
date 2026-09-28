package com.jobber.core.job;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

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
