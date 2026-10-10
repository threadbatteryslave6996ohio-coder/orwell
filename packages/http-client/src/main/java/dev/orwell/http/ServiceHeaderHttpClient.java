package dev.orwell.http;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Wraps an existing {@link HttpClient} so every request it sends carries a {@code Service} header
 * naming the process that sent it. A server on the other end can then log which service each
 * request came from, instead of a remote address that only identifies a container.
 *
 * <p>It is a drop-in: the wrapper <em>is</em> an {@code HttpClient}, so an existing client only
 * changes where it is built, and no call site changes.
 *
 * <pre>{@code
 * // before
 * private final HttpClient http = HttpClient.newHttpClient();
 *
 * // after
 * private final HttpClient http = ServiceHeaderHttpClient.wrap(HttpClient.newHttpClient(), "log-analyzer");
 * }</pre>
 *
 * <p>Rules that hold by construction:
 *
 * <ul>
 *   <li><b>A header set at the call site wins.</b> A request that already carries {@code Service}
 *       is forwarded untouched — so a caller can attribute one request to something more specific,
 *       and so a relay (the reverse proxy) passes the original sender's name through rather than
 *       overwriting it with its own.
 *   <li><b>Attribution never breaks a request.</b> If the JDK refuses to copy a request, the
 *       original is sent unstamped. A missing log field is a smaller failure than a request that
 *       stopped working because someone wanted better logs.
 *   <li><b>The name is validated once, at construction.</b> A blank name, or one carrying
 *       characters illegal in a header value, fails at startup rather than at the first request.
 * </ul>
 *
 * <p>Every other method delegates via {@link DelegatingHttpClient}, including {@link #close()} and
 * the rest of the shutdown family, so the wrapper owns nothing and closing it closes the client it
 * wraps.
 */
public final class ServiceHeaderHttpClient extends DelegatingHttpClient {
    /** The header carrying the sending service's name. HTTP header names are case-insensitive. */
    public static final String SERVICE_HEADER = "Service";

    private final String service;

    private ServiceHeaderHttpClient(HttpClient delegate, String service) {
        super(delegate);
        this.service = validated(service);
    }

    /**
     * Returns a client that sends everything {@code delegate} would, with {@code Service: service}
     * added to each request.
     *
     * <p>Wrapping an already-wrapped client is harmless: the outermost name is the one applied,
     * because the inner wrapper sees the header already set and leaves it alone.
     *
     * @param service the sending service's name — conventionally the same string the process
     *                passes to {@code AppServer} or to {@code LoggerSetup.fromConfiguration}, so
     *                the sender's own logs and the receiver's agree on what to call it
     * @throws IllegalArgumentException if {@code service} is blank or contains a character that
     *                                  cannot appear in a header value
     */
    public static HttpClient wrap(HttpClient delegate, String service) {
        return new ServiceHeaderHttpClient(delegate, service);
    }

    /** The name this client stamps on every request. */
    public String service() {
        return service;
    }

    @Override
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler)
            throws IOException, InterruptedException {
        return delegate.send(stamped(request), responseBodyHandler);
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
        return delegate.sendAsync(stamped(request), responseBodyHandler);
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            HttpRequest request,
            HttpResponse.BodyHandler<T> responseBodyHandler,
            HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
        return delegate.sendAsync(stamped(request), responseBodyHandler, pushPromiseHandler);
    }

    /**
     * Copies the request with the attribution headers added. The copy keeps the method, URI, body
     * publisher, timeout, version and every existing header, so the only difference on the wire is
     * the one or two lines this class exists to add.
     *
     * <p>{@link RequestId#HEADER} rides along whenever this send happens inside a request the
     * server has an id for. That is what makes the two hops joinable: the caller's log line and
     * the callee's carry the same id, without either of them being asked to pass one explicitly.
     */
    private HttpRequest stamped(HttpRequest request) {
        Objects.requireNonNull(request, "request");
        boolean needsService = request.headers().firstValue(SERVICE_HEADER).isEmpty();
        String requestId = request.headers().firstValue(RequestId.HEADER).isPresent()
                ? null
                : RequestId.current();
        if (!needsService && requestId == null) {
            return request;
        }
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(request, (name, value) -> true);
            if (needsService) {
                builder.header(SERVICE_HEADER, service);
            }
            if (requestId != null) {
                builder.header(RequestId.HEADER, requestId);
            }
            return builder.build();
        } catch (IllegalArgumentException exception) {
            // A request that exists was already accepted by the builder once, so this is close to
            // unreachable — but "close to" is not "never", and losing a log field beats turning a
            // working call into an exception the caller has no way to handle.
            return request;
        }
    }

    private static String validated(String service) {
        Objects.requireNonNull(service, "service");
        if (service.isBlank()) {
            throw new IllegalArgumentException("service must not be blank");
        }
        for (int index = 0; index < service.length(); index++) {
            char character = service.charAt(index);
            // Anything outside printable US-ASCII would be rejected per request by java.net.http
            // (or, for CR/LF, would be a header injection). Refuse it once, at construction.
            if (character < 0x20 || character > 0x7E) {
                throw new IllegalArgumentException(
                        "service must be printable US-ASCII, so it can be sent as a header value: " + service);
            }
        }
        return service;
    }
}
