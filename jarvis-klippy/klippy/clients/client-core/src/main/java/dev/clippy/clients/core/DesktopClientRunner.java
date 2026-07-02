package dev.clippy.clients.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Owns the shared runtime lifecycle for the desktop clipboard clients: the polling
 * scheduler, its shutdown hook, and the one-line startup banner. Platform mains keep
 * only their clipboard-reader construction and hand the assembled monitor here.
 */
public final class DesktopClientRunner {
    private static final String SHUTDOWN_TIMEOUT_MESSAGE = "Timed out while stopping clipboard client.";

    private final DesktopClipboardMonitor monitor;
    private final long pollIntervalMs;

    public DesktopClientRunner(DesktopClipboardMonitor monitor, long pollIntervalMs) {
        this.monitor = Objects.requireNonNull(monitor, "monitor");
        this.pollIntervalMs = pollIntervalMs;
    }

    /**
     * Prints the startup banner, installs the shutdown hook, and schedules polling.
     *
     * @param label       leading text of the banner, e.g. {@code "Clippy client started."}
     * @param config      client configuration used to render the shared banner fields
     * @param extraFields additional {@code key=value} banner fields, inserted before
     *                    {@code tokenSource} in iteration order
     */
    public void start(String label, ClientConfig config, Map<String, String> extraFields) {
        System.out.println(startupBanner(label, config, pollIntervalMs, extraFields));

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        Runtime.getRuntime().addShutdownHook(new Thread(() ->
                ExecutorShutdown.shutdown(scheduler, SHUTDOWN_TIMEOUT_MESSAGE)));
        scheduler.scheduleWithFixedDelay(monitor::poll, 0, pollIntervalMs, TimeUnit.MILLISECONDS);
    }

    public void start(String label, ClientConfig config) {
        start(label, config, Map.of());
    }

    static String startupBanner(String label, ClientConfig config, long pollIntervalMs, Map<String, String> extraFields) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("clientId", config.clientId());
        fields.put("endpoint", String.valueOf(config.endpoint()));
        fields.put("authServer", config.authServerUrl() == null ? "unset" : config.authServerUrl());
        fields.put("pollIntervalMs", Long.toString(pollIntervalMs));
        fields.putAll(extraFields);
        fields.put("tokenSource", config.authSession().canRefresh() ? "CLIENT_SECRET" : "CLIENT_TOKEN");

        StringBuilder banner = new StringBuilder(label);
        fields.forEach((key, value) -> banner.append(' ').append(key).append('=').append(value));
        return banner.toString();
    }
}
