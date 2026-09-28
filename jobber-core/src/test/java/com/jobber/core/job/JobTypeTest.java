package com.jobber.core.job;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JobTypeTest {

    @Test
    void resolvesKnownTypeNames() {
        assertThat(JobType.fromTypeName("email.send")).contains(JobType.EMAIL_SEND);
        assertThat(JobType.fromTypeName("report.generate")).contains(JobType.REPORT_GENERATE);
    }

    @Test
    void rejectsUnknownOrDifferentlyCasedNames() {
        assertThat(JobType.fromTypeName("sms.send")).isEmpty();
        assertThat(JobType.fromTypeName("EMAIL_SEND")).isEmpty();
        assertThat(JobType.fromTypeName("Email.Send")).isEmpty();
        assertThat(JobType.fromTypeName(null)).isEmpty();
    }
}
