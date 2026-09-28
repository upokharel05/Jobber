package com.jobber.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// Also scan jobber-core so shared beans such as JobRepository are picked up.
@SpringBootApplication(scanBasePackages = {"com.jobber.api", "com.jobber.core"})
public class JobberApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(JobberApiApplication.class, args);
    }
}
