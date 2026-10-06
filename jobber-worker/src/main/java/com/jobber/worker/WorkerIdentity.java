package com.jobber.worker;

import java.net.InetAddress;
import java.net.UnknownHostException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * A unique name for this worker process, recorded on every job it claims. Defaults to
 * {@code hostname-pid}; unique per container when running in Docker. Override with {@code jobber.worker.id}.
 */
@Component
public class WorkerIdentity {

    private final String id;

    public WorkerIdentity(@Value("${jobber.worker.id:}") String configuredId) {
        this.id = StringUtils.hasText(configuredId) ? configuredId : hostname() + "-" + ProcessHandle.current().pid();
    }

    public String id() {
        return id;
    }

    private static String hostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown-host";
        }
    }
}
