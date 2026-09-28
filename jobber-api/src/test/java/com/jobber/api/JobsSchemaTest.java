package com.jobber.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/** Verifies the Flyway migration and that the database itself rejects invalid jobs. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional  // each test's inserts are rolled back
class JobsSchemaTest {

    @Autowired
    JdbcClient jdbc;

    @Test
    void insertsJobWithDefaults() {
        Long id = jdbc.sql("""
                        INSERT INTO jobs (type, payload, status, run_at)
                        VALUES ('email.send', '{"to": "a@example.com"}'::jsonb, 'SCHEDULED', now())
                        RETURNING id
                        """)
                .query(Long.class)
                .single();

        var row = jdbc.sql("SELECT priority, attempt_count, max_attempts, payload->>'to' AS recipient FROM jobs WHERE id = ?")
                .param(id)
                .query()
                .singleRow();

        assertThat(row).containsEntry("priority", 2)  // the JDBC driver returns SMALLINT as Integer
                .containsEntry("attempt_count", 0)
                .containsEntry("max_attempts", 3)
                .containsEntry("recipient", "a@example.com");
    }

    @Test
    void rejectsUnknownStatus() {
        assertThatThrownBy(() -> insert("email.send", "BOGUS", null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsDuplicateIdempotencyKey() {
        insert("email.send", "SCHEDULED", "key-1");
        assertThatThrownBy(() -> insert("email.send", "SCHEDULED", "key-1"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void allowsManyJobsWithoutIdempotencyKey() {
        insert("email.send", "SCHEDULED", null);
        insert("email.send", "SCHEDULED", null);
    }

    private void insert(String type, String status, String idempotencyKey) {
        jdbc.sql("INSERT INTO jobs (type, status, run_at, idempotency_key) VALUES (?, ?, now(), ?)")
                .params(type, status, idempotencyKey)
                .update();
    }
}
