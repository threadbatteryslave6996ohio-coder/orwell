package dev.orwell.http;

import dev.orwell.logging.LogLevel;
import dev.orwell.logging.Logger;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Wraps an existing {@link HttpClient} so every request it sends is written to a
 * {@link Logger} — one record per request, carrying the method, the target, the status and how
 * long it took.
 *
 * <p>Like {@link ServiceHeaderHttpClient}, it is a drop-in: the wrapper <em>is</em> an
 * {@code HttpClient}, so an existing client only changes where it is built, and no call site
 * changes. The two compose, and reading outwards is reading the order things happen in:
 *
 * <pre>{@code
 * // before
 * private final HttpClient http = HttpClient.newHttpClient();
 *
 * // after — stamped with the sender's name, and every call recorded
 * private final HttpClient http = LoggingHttpClient.wrap(
 *         ServiceHeaderHttpClient.wrap(HttpClient.newHttpClient(), "secrets-manager-client"),
 *         logger);
 * }</pre>
 *
 * <p>Rules that hold by construction:
 *
 * <ul>
 *   <li><b>One record, written when the request finishes.</b> A line without a status or a
 *       duration is barely worth the volume, and the repo's sinks do not filter by level — so
 *       this does not also write a line when the request starts. The cost is that a call which
 *       never returns leaves no record; every client in this repo sets a connect timeout, so it
 *       does return, as a failure.
 *   <li><b>Logging never breaks a request.</b> A sink that throws is swallowed here, and a failed
 *       request is logged and then rethrown unchanged. Wrap the logger in
 *       {@code FailSafeLogger} if you want the failure itself reported somewhere.
 *   <li><b>No headers, no bodies, no query strings.</b> {@code Authorization} is on nearly every
 *       request in this repo and tokens travel in query strings too (Apify's do), so the target
 *       is logged up to the first {@code ?}. What is left is the part that identifies the
 *       endpoint, which is the part worth aggregating on anyway.
 * </ul>
 *
 * <p><b>Do not give a logging sink's own transport this wrapper with that same sink's logger.</b>
 * {@code LokiLogger} pushes over HTTP; logging its pushes through it is a loop that feeds itself.
 *
 * <p>Every other method delegates via {@link DelegatingHttpClient}, including {@link #close()} and
 * the rest of the shutdown family, so the wrapper owns nothing and closing it closes the client it
 * wraps.
 */
public final class LoggingHttpClient extends DelegatingHttpClient {
    /** The level a request that produced a response is recorded at, unless the caller picks one. */
    public static final LogLevel DEFAULT_LEVEL = LogLevel.INFO;

    private static final String COMPLETED_MESSAGE = "Sent HTTP request.";
    private static final String FAILED_MESSAGE = "HTTP request failed.";

    private final Logger logger;
    private final LogLevel level;

    private LoggingHttpClient(HttpClient delegate, Logger logger, LogLevel level) {
        super(delegate);
        this.logger = Objects.requireNonNull(logger, "logger");
        this.level = Objects.requireNonNull(level, "level");
    }

    /**
     * Returns a client that sends everything {@code delegate} would, and records each request at
     * {@link #DEFAULT_LEVEL} once it completes.
     *
     * @param logger where the records go — the same {@code Logger} the calling component already
     *               has, so its outbound calls land in the same stream as the rest of its work
     */
    public static HttpClient wrap(HttpClient delegate, Logger logger) {
        return new LoggingHttpClient(delegate, logger, DEFAULT_LEVEL);
    }

    /**
     * Returns a client that records completed requests at {@code level} rather than
     * {@link #DEFAULT_LEVEL} — for a poller that would otherwise put a line in the stream every
     * second. A request that <em>failed</em> is always {@code WARN}: a caller who chose a quieter
     * level for the ordinary case did not thereby ask to lose the interesting one.
     */
    public static HttpClient wrap(HttpClient delegate, Logger logger, LogLevel level) {
        return new LoggingHttpClient(delegate, logger, level);
    }

    /** The level completed requests are recorded at. */
    public LogLevel level() {
        return level;
    }

    @Override
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler)
            throws IOException, InterruptedException {
        Objects.requireNonNull(request, "request");
        long startedAt = System.nanoTime();
        try {
            HttpResponse<T> response = delegate.send(request, responseBodyHandler);
            recordCompleted(request, response.statusCode(), startedAt);
            return response;
        } catch (IOException | InterruptedException | RuntimeException exception) {
            recordFailed(request, exception, startedAt);
            throw exception;
        }
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
        Objects.requireNonNull(request, "request");
        long startedAt = System.nanoTime();
        return recording(request, delegate.sendAsync(request, responseBodyHandler), startedAt);
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            HttpRequest request,
            HttpResponse.BodyHandler<T> responseBodyHandler,
            HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
        Objects.requireNonNull(request, "request");
        long startedAt = System.nanoTime();
        return recording(
                request, delegate.sendAsync(request, responseBodyHandler, pushPromiseHandler), startedAt);
    }

    /**
     * Attaches the record to {@code future} and hands back <em>that same future</em>, not the one
     * {@code whenComplete} returns: cancelling a derived future does not cancel the request behind
     * it, and a caller who cancels means the request.
     *
     * <p>The consequence is that the record and the caller's own continuation race, so on the
     * async path a line can land just after the code that was waiting for the response has already
     * moved on. That is the right way round — a log line is not worth delaying a response for.
     */
    private <T> CompletableFuture<HttpResponse<T>> recording(
            HttpRequest request, CompletableFuture<HttpResponse<T>> future, long startedAt) {
        future.whenComplete((response, throwable) -> {
            if (throwable == null) {
                recordCompleted(request, response.statusCode(), startedAt);
            } else {
                recordFailed(request, unwrapped(throwable), startedAt);
            }
        });
        return future;
    }

    /** {@code whenComplete} reports a {@link CompletionException} wrapper; the cause is the news. */
    private static Throwable unwrapped(Throwable throwable) {
        return throwable instanceof CompletionException && throwable.getCause() != null
                ? throwable.getCause()
                : throwable;
    }

    private void recordCompleted(HttpRequest request, int status, long startedAt) {
        Map<String, Object> metadata = baseMetadata(request, startedAt);
        metadata.put("status", status);
        write(level, COMPLETED_MESSAGE, metadata);
    }

    private void recordFailed(HttpRequest request, Throwable throwable, long startedAt) {
        Map<String, Object> metadata = baseMetadata(request, startedAt);
        // There is no (String, Throwable) overload on Logger, by design: exception detail is
        // metadata like everything else.
        metadata.put("error", String.valueOf(throwable));
        write(LogLevel.WARN, FAILED_MESSAGE, metadata);
    }

    private Map<String, Object> baseMetadata(HttpRequest request, long startedAt) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("method", request.method());
        metadata.put("uri", withoutQuery(request.uri()));
        metadata.put("durationMs", Duration.ofNanos(System.nanoTime() - startedAt).toMillis());
        return metadata;
    }

    private void write(LogLevel recordLevel, String message, Map<String, Object> metadata) {
        try {
            logger.log(recordLevel, message, metadata);
        } catch (RuntimeException exception) {
            // Deliberately swallowed, and deliberately not reported: this class has no sink of its
            // own to report to, and a request that stopped working because someone wanted better
            // logs is a worse failure than a missing line. FailSafeLogger is where a sink failure
            // gets handled.
        }
    }

    /**
     * The target up to the first {@code ?} or {@code #}. Query strings carry credentials often
     * enough that logging them is a liability, and the path is what identifies the endpoint.
     */
    private static String withoutQuery(java.net.URI uri) {
        String text = uri.toString();
        int cut = text.length();
        int question = text.indexOf('?');
        int hash = text.indexOf('#');
        if (question >= 0) {
            cut = question;
        }
        if (hash >= 0 && hash < cut) {
            cut = hash;
        }
        return text.substring(0, cut);
    }
}
