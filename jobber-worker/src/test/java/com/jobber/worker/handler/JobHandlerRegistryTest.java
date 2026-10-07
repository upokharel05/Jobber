package com.jobber.worker.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.jobber.core.job.JobType;

class JobHandlerRegistryTest {

    @Test
    void resolvesHandlerByTypeName() {
        JobHandlerRegistry registry = new JobHandlerRegistry(oneHandlerPerType());

        assertThat(registry.handlerFor("report.generate").type()).isEqualTo(JobType.REPORT_GENERATE);
    }

    @Test
    void unknownTypeIsAPermanentFailure() {
        JobHandlerRegistry registry = new JobHandlerRegistry(oneHandlerPerType());

        assertThatThrownBy(() -> registry.handlerFor("sms.send"))
                .isInstanceOf(PermanentJobFailureException.class);
    }

    @Test
    void refusesToStartWithAMissingHandler() {
        List<JobHandler> handlers = oneHandlerPerType();
        handlers.removeIf(h -> h.type() == JobType.FILE_EXPORT);

        assertThatThrownBy(() -> new JobHandlerRegistry(handlers))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FILE_EXPORT");
    }

    @Test
    void refusesToStartWithDuplicateHandlers() {
        List<JobHandler> handlers = oneHandlerPerType();
        handlers.add(new SimulatedJobHandler(JobType.EMAIL_SEND, Duration.ZERO));

        assertThatThrownBy(() -> new JobHandlerRegistry(handlers))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EMAIL_SEND");
    }

    private static List<JobHandler> oneHandlerPerType() {
        return new ArrayList<>(Arrays.stream(JobType.values())
                .map(t -> (JobHandler) new SimulatedJobHandler(t, Duration.ZERO))
                .toList());
    }
}
