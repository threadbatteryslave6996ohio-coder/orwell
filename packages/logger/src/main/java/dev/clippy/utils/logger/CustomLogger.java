package dev.clippy.utils.logger;

public final class CustomLogger {
    private final dev.clippy.utils.CustomLogger delegate;

    public static void configureDirectoryFromLogFile(String logFileName) {
        dev.clippy.utils.CustomLogger.configureDirectoryFromLogFile(logFileName);
    }

    public CustomLogger(String name) {
        this.delegate = new dev.clippy.utils.CustomLogger(name);
    }

    public void log(String message) {
        delegate.log(message);
    }
}
