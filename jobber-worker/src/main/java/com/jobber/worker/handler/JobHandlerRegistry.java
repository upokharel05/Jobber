package com.jobber.worker.handler;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.jobber.core.job.JobType;

/**
 * Maps each {@link JobType} to its handler. Fails at startup if any type lacks a handler (or has
 * two), so a worker can never be deployed that accepts jobs it cannot run.
 */
@Component
public class JobHandlerRegistry {

    private final Map<JobType, JobHandler> handlers = new EnumMap<>(JobType.class);

    public JobHandlerRegistry(List<JobHandler> handlerBeans) {
        for (JobHandler handler : handlerBeans) {
            if (handlers.putIfAbsent(handler.type(), handler) != null) {
                throw new IllegalStateException("More than one handler for job type " + handler.type());
            }
        }
        List<JobType> missing = Arrays.stream(JobType.values()).filter(t -> !handlers.containsKey(t)).toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("No handler registered for job types " + missing);
        }
    }

    /** @throws PermanentJobFailureException for a type this worker doesn't know; retrying won't help */
    public JobHandler handlerFor(String typeName) {
        JobType type = JobType.fromTypeName(typeName)
                .orElseThrow(() -> new PermanentJobFailureException("Unknown job type '" + typeName + "'"));
        return handlers.get(type);
    }
}
