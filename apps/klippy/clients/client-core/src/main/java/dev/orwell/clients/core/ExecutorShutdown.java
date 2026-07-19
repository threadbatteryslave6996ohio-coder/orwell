package dev.orwell.clients.core;

import dev.orwell.logging.Logger;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class ExecutorShutdown {
    private ExecutorShutdown() {
    }

    public static void shutdown(ScheduledExecutorService scheduler, String timeoutMessage, Logger logger) {
        scheduler.shutdownNow();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                logger.error(timeoutMessage);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
