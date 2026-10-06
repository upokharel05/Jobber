package com.jobber.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// Also scan jobber-core so shared beans (JobRepository, queue topology) are picked up.
@SpringBootApplication(scanBasePackages = {"com.jobber.worker", "com.jobber.core"})
public class JobberWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(JobberWorkerApplication.class, args);
    }
}
