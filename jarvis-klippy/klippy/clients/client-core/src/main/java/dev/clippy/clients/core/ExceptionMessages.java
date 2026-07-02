package dev.clippy.clients.core;

public final class ExceptionMessages {
    private ExceptionMessages() {
    }

    public static String message(Throwable exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    public static String messageWithCause(RuntimeException exception) {
        String message = message(exception);
        Throwable cause = exception.getCause();
        return cause == null || cause.getMessage() == null || cause.getMessage().isBlank()
                ? message
                : message + ": " + cause.getMessage();
    }
}
