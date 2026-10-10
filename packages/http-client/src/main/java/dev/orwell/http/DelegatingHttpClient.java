package dev.orwell.http;

import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

/**
 * Base for an {@link HttpClient} that wraps another one to add a single behaviour to sending, and
 * changes nothing else. It implements the whole configuration and shutdown surface as pass-throughs
 * to {@link #delegate}, leaving a subclass with only the three {@code send}/{@code sendAsync}
 * methods to override.
 *
 * <p>{@link ServiceHeaderHttpClient} and {@link LoggingHttpClient} are the two in this repo, and
 * they compose — reading outwards is reading the order things happen in:
 *
 * <pre>{@code
 * private final HttpClient http = LoggingHttpClient.wrap(
 *         ServiceHeaderHttpClient.wrap(HttpClient.newHttpClient(), "secrets-manager-client"),
 *         logger);
 * }</pre>
 *
 * <p>Rules a subclass inherits, and should not undo:
 *
 * <ul>
 *   <li><b>The wrapper owns nothing.</b> {@link #close()} and the rest of the shutdown family
 *       delegate, so closing the wrapper closes the client it wraps and nothing else.
 *   <li><b>The added behaviour never breaks a request.</b> Whatever a subclass does around the
 *       send — stamping a header, writing a log record — a failure in <em>that</em> must not turn
 *       a working call into an exception the caller has no way to handle.
 * </ul>
 */
public abstract class DelegatingHttpClient extends HttpClient {
    /** The client every method here forwards to, and that a subclass sends through. */
    protected final HttpClient delegate;

    protected DelegatingHttpClient(HttpClient delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public Optional<CookieHandler> cookieHandler() {
        return delegate.cookieHandler();
    }

    @Override
    public Optional<Duration> connectTimeout() {
        return delegate.connectTimeout();
    }

    @Override
    public Redirect followRedirects() {
        return delegate.followRedirects();
    }

    @Override
    public Optional<ProxySelector> proxy() {
        return delegate.proxy();
    }

    @Override
    public SSLContext sslContext() {
        return delegate.sslContext();
    }

    @Override
    public SSLParameters sslParameters() {
        return delegate.sslParameters();
    }

    @Override
    public Optional<Authenticator> authenticator() {
        return delegate.authenticator();
    }

    @Override
    public Version version() {
        return delegate.version();
    }

    @Override
    public Optional<Executor> executor() {
        return delegate.executor();
    }

    /**
     * Delegates, so a subclass's behaviour does not reach a WebSocket. The handshake is built by
     * the returned builder, which no wrapper in this chain ever sees, and what follows it is frames
     * rather than requests — a different thing to stamp or to log. A service that needs a header on
     * a handshake adds it on that builder.
     */
    @Override
    public WebSocket.Builder newWebSocketBuilder() {
        return delegate.newWebSocketBuilder();
    }

    @Override
    public void shutdown() {
        delegate.shutdown();
    }

    @Override
    public void shutdownNow() {
        delegate.shutdownNow();
    }

    @Override
    public boolean awaitTermination(Duration duration) throws InterruptedException {
        return delegate.awaitTermination(duration);
    }

    @Override
    public boolean isTerminated() {
        return delegate.isTerminated();
    }

    @Override
    public void close() {
        delegate.close();
    }
}
