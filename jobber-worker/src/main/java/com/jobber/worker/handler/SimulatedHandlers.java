package com.jobber.worker.handler;

import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.jobber.core.job.JobType;

/** Registers a simulated handler per job type until real handlers replace them. */
@Configuration(proxyBeanMethods = false)
class SimulatedHandlers {

    @Bean
    JobHandler emailSendHandler() {
        return new SimulatedJobHandler(JobType.EMAIL_SEND, Duration.ofMillis(50));
    }

    @Bean
    JobHandler reportGenerateHandler() {
        return new SimulatedJobHandler(JobType.REPORT_GENERATE, Duration.ofMillis(300));
    }

    @Bean
    JobHandler dataProcessHandler() {
        return new SimulatedJobHandler(JobType.DATA_PROCESS, Duration.ofMillis(200));
    }

    @Bean
    JobHandler fileExportHandler() {
        return new SimulatedJobHandler(JobType.FILE_EXPORT, Duration.ofMillis(150));
    }
}
