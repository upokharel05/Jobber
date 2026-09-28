package com.jobber.api.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.jayway.jsonpath.JsonPath;
import com.jobber.api.TestcontainersConfiguration;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class JobControllerTest {

    @Autowired
    MockMvcTester mvc;

    @Test
    void submitsJobWithDefaultsAndReturns201WithLocation() {
        MvcTestResult result = submit("""
                {"type": "email.send", "payload": {"to": "a@example.com"}}
                """, null);

        assertThat(result).hasStatus(HttpStatus.CREATED);
        long id = idOf(result);
        assertThat(result.getResponse().getHeader("Location")).endsWith("/jobs/" + id);
        assertThat(result).bodyJson()
                .hasPathSatisfying("$.status", v -> v.assertThat().isEqualTo("SCHEDULED"))
                .hasPathSatisfying("$.priority", v -> v.assertThat().isEqualTo("NORMAL"))
                .hasPathSatisfying("$.maxAttempts", v -> v.assertThat().isEqualTo(3))
                .hasPathSatisfying("$.attemptCount", v -> v.assertThat().isEqualTo(0))
                .hasPathSatisfying("$.payload.to", v -> v.assertThat().isEqualTo("a@example.com"))
                .hasPathSatisfying("$.runAt", v -> v.assertThat().isNotNull());
    }

    @Test
    void honorsOptionalFields() {
        MvcTestResult result = submit("""
                {"type": "report.generate", "priority": "HIGH", "maxAttempts": 5, "runAt": "2030-01-01T00:00:00Z"}
                """, null);

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(result).bodyJson()
                .hasPathSatisfying("$.priority", v -> v.assertThat().isEqualTo("HIGH"))
                .hasPathSatisfying("$.maxAttempts", v -> v.assertThat().isEqualTo(5))
                .hasPathSatisfying("$.runAt", v -> v.assertThat().isEqualTo("2030-01-01T00:00:00Z"))
                .hasPathSatisfying("$.payload", v -> v.assertThat().asMap().isEmpty());
    }

    @Test
    void getReturnsSubmittedJob() {
        long id = idOf(submit("""
                {"type": "file.export", "payload": {"format": "csv"}}
                """, null));

        assertThat(mvc.get().uri("/jobs/{id}", id))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.id", v -> v.assertThat().isEqualTo((int) id))
                .hasPathSatisfying("$.type", v -> v.assertThat().isEqualTo("file.export"))
                .hasPathSatisfying("$.payload.format", v -> v.assertThat().isEqualTo("csv"));
    }

    @Test
    void getUnknownJobReturns404ProblemDetail() {
        assertThat(mvc.get().uri("/jobs/{id}", Long.MAX_VALUE))
                .hasStatus(HttpStatus.NOT_FOUND)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .hasPathSatisfying("$.title", v -> v.assertThat().isEqualTo("Job not found"));
    }

    @Test
    void reusedIdempotencyKeyReturnsOriginalJobWith200() {
        String key = UUID.randomUUID().toString();
        MvcTestResult first = submit("""
                {"type": "email.send", "payload": {"to": "a@example.com"}}
                """, key);
        // Different body, same key: still the original job, by design.
        MvcTestResult second = submit("""
                {"type": "data.process", "payload": {"rows": 10}}
                """, key);

        assertThat(first).hasStatus(HttpStatus.CREATED);
        assertThat(second).hasStatus(HttpStatus.OK);
        assertThat(idOf(second)).isEqualTo(idOf(first));
        assertThat(second).bodyJson()
                .hasPathSatisfying("$.type", v -> v.assertThat().isEqualTo("email.send"))
                .hasPathSatisfying("$.idempotencyKey", v -> v.assertThat().isEqualTo(key));
    }

    @Test
    void rejectsUnknownTypeAndListsSupportedTypes() {
        assertThat(submit("""
                {"type": "sms.send"}
                """, null))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .hasPathSatisfying("$.title", v -> v.assertThat().isEqualTo("Unknown job type"))
                .hasPathSatisfying("$.supportedTypes", v -> v.assertThat().asArray().contains("email.send", "file.export"));
    }

    @Test
    void rejectsInvalidRequests() {
        assertThat(submit("{}", null)).hasStatus(HttpStatus.BAD_REQUEST);                                              // missing type
        assertThat(submit("""
                {"type": "email.send", "payload": [1, 2]}
                """, null)).hasStatus(HttpStatus.BAD_REQUEST);                                                          // payload not an object
        assertThat(submit("""
                {"type": "email.send", "maxAttempts": 0}
                """, null)).hasStatus(HttpStatus.BAD_REQUEST);                                                          // out of range
        assertThat(submit("""
                {"type": "email.send", "priority": "URGENT"}
                """, null)).hasStatus(HttpStatus.BAD_REQUEST);                                                          // unknown priority
        assertThat(submit("{not json", null)).hasStatus(HttpStatus.BAD_REQUEST);                                        // malformed
        assertThat(submit("""
                {"type": "email.send"}
                """, "x".repeat(256))).hasStatus(HttpStatus.BAD_REQUEST);                                               // key too long
    }

    private MvcTestResult submit(String json, String idempotencyKey) {
        var request = mvc.post().uri("/jobs").contentType(MediaType.APPLICATION_JSON).content(json);
        if (idempotencyKey != null) {
            request = request.header("Idempotency-Key", idempotencyKey);
        }
        return request.exchange();
    }

    private static long idOf(MvcTestResult result) {
        try {
            return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
        } catch (Exception e) {
            throw new AssertionError("Response has no id", e);
        }
    }
}
