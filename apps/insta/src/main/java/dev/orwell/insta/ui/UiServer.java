package dev.orwell.insta.ui;

import dev.orwell.logging.Logger;
import dev.orwell.undertow.UndertowHttp;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.RoutingHandler;
import io.undertow.util.Headers;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A read-only viewer for the follow graph, on the repo's lightweight Undertow runtime rather than a
 * Spring stack — three endpoints and a page do not need a container.
 *
 * <pre>
 * GET /                             the page
 * GET /api/accounts                 accounts a sync has walked
 * GET /api/graph?account=&amp;inactive= the neighbourhood, as nodes and links
 * GET /api/unfollows?account=       recent departures
 * </pre>
 *
 * <p>A connection is opened per request and closed after it. For a viewer somebody has open in one
 * tab that is cheaper than owning a pool, and it means a database restart costs one failed refresh
 * instead of a permanently broken server.
 *
 * <p><b>There is no authentication.</b> Bound to all interfaces, as asked, this serves scraped
 * personal data — handles, bios, follower lists — to anything that can reach the port. It logs a
 * warning saying so at startup, and {@code INSTA_UI_ADDRESS=127.0.0.1} restricts it to this machine.
 */
public final class UiServer {
    private static final String PAGE = "/dev/orwell/insta/ui/ui.html";

    private final ConnectionSource connections;
    private final Logger logger;

    public UiServer(ConnectionSource connections, Logger logger) {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /** Opens a fresh database connection. */
    @FunctionalInterface
    public interface ConnectionSource {
        Connection open() throws SQLException;
    }

    public HttpHandler handler() {
        RoutingHandler routes = UndertowHttp.routes();
        routes.get("/", this::page);
        routes.get("/api/accounts", withConnection(this::accounts));
        routes.get("/api/graph", withConnection(this::graph));
        routes.get("/api/unfollows", withConnection(this::unfollows));
        routes.get("/health", exchange -> UndertowHttp.sendJson(
                exchange, 200, UndertowHttp.health(Map.of("app", "insta-ui"))));
        return routes;
    }

    public void startAndWait(String address, int port) throws InterruptedException {
        logger.warn("The insta UI has no authentication; anything that can reach this port can "
                + "read the graph.", Map.of("address", address, "port", port));
        logger.info("insta UI listening.", Map.of("url", "http://" + address + ":" + port));
        UndertowHttp.startAndWait(address, port, handler());
    }

    private void page(HttpServerExchange exchange) throws IOException {
        try (InputStream stream = UiServer.class.getResourceAsStream(PAGE)) {
            if (stream == null) {
                UndertowHttp.sendError(exchange, 500, "ui page is missing from the jar");
                return;
            }
            byte[] body = stream.readAllBytes();
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/html; charset=utf-8");
            exchange.getResponseHeaders().put(Headers.CONTENT_LENGTH, Integer.toString(body.length));
            exchange.getResponseSender().send(ByteBuffer.wrap(body));
        }
    }

    private void accounts(HttpServerExchange exchange, Connection connection) throws Exception {
        UndertowHttp.sendJson(exchange, 200, GraphQueries.walkedAccounts(connection));
    }

    private void graph(HttpServerExchange exchange, Connection connection) throws Exception {
        Optional<String> subject = subject(exchange, connection);
        if (subject.isEmpty()) {
            UndertowHttp.sendError(exchange, 404, "no such account in the graph");
            return;
        }
        boolean inactive = "true".equals(parameter(exchange, "inactive"));
        UndertowHttp.sendJson(exchange, 200,
                GraphQueries.neighbourhood(connection, subject.get(), inactive));
    }

    private void unfollows(HttpServerExchange exchange, Connection connection) throws Exception {
        Optional<String> subject = subject(exchange, connection);
        if (subject.isEmpty()) {
            UndertowHttp.sendError(exchange, 404, "no such account in the graph");
            return;
        }
        List<Map<String, Object>> departures =
                GraphQueries.recentUnfollows(connection, subject.get(), 100);
        UndertowHttp.sendJson(exchange, 200, departures);
    }

    private Optional<String> subject(HttpServerExchange exchange, Connection connection)
            throws SQLException {
        String account = parameter(exchange, "account");
        if (account == null || account.isBlank()) {
            // No account named: default to whichever has the most followers, so the page shows
            // something useful on first load rather than an empty canvas.
            return GraphQueries.walkedAccounts(connection).stream()
                    .findFirst().map(GraphView.Node::id);
        }
        return GraphQueries.resolve(connection, account);
    }

    private static String parameter(HttpServerExchange exchange, String name) {
        var values = exchange.getQueryParameters().get(name);
        return values == null || values.isEmpty() ? null : values.getFirst();
    }

    /**
     * Wraps a handler with a connection it owns. A database that is down becomes a 503 on one
     * request rather than an exception that takes the listener with it.
     */
    private HttpHandler withConnection(DatabaseHandler handler) {
        return exchange -> {
            try (Connection connection = connections.open()) {
                handler.handle(exchange, connection);
            } catch (Exception exception) {
                logger.error("UI request failed.", Map.of(
                        "path", exchange.getRequestPath(), "error", exception.toString()));
                UndertowHttp.sendError(exchange, 503, "could not read the graph");
            }
        };
    }

    @FunctionalInterface
    private interface DatabaseHandler {
        void handle(HttpServerExchange exchange, Connection connection) throws Exception;
    }

    static String pageResource() {
        try (InputStream stream = UiServer.class.getResourceAsStream(PAGE)) {
            return stream == null ? null : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            return null;
        }
    }
}
