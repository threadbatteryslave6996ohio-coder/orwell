package dev.orwell.clients.linux.clipboard;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

/**
 * Reads the clipboard by running an external command (wl-paste, xclip, xsel) and
 * capturing its standard output.
 */
public final class CommandClipboardReader implements LinuxClipboardReader {
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(2);

    private final String name;
    private final List<String> command;

    public CommandClipboardReader(String name, List<String> command) {
        this.name = name;
        this.command = command;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public boolean isAvailable() {
        return Executables.exists(command.get(0));
    }

    @Override
    public String readText() throws IOException, InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder(command)
                .redirectErrorStream(true);
        // Poll helpers are not desktop applications and must not inherit the
        // launch identity of the terminal or IDE that started Klippy.
        ProcessEnvironmentSanitizer.stripDesktopLaunchEnvironment(processBuilder.environment());
        Process process = processBuilder.start();
        CompletableFuture<byte[]> output = CompletableFuture.supplyAsync(() -> {
            try {
                return readAll(process.getInputStream());
            } catch (IOException exception) {
                throw new CompletionException(exception);
            }
        });

        boolean finished = process.waitFor(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException(name + " timed out");
        }

        byte[] stdout = readProcessOutput(output);
        int exitCode = process.exitValue();
        if (exitCode != 0) {
            String message = new String(stdout, StandardCharsets.UTF_8).trim();
            if (message.isBlank()) {
                message = name + " exited with status " + exitCode;
            }
            throw new IOException(message);
        }

        String text = new String(stdout, StandardCharsets.UTF_8);
        return text.isEmpty() ? null : text;
    }

    private static byte[] readProcessOutput(CompletableFuture<byte[]> output) throws IOException {
        try {
            return output.join();
        } catch (CompletionException exception) {
            if (exception.getCause() instanceof IOException ioException) {
                throw ioException;
            }
            throw exception;
        }
    }

    private static byte[] readAll(InputStream inputStream) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        inputStream.transferTo(output);
        return output.toByteArray();
    }
}
