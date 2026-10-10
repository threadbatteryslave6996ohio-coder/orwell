package dev.orwell.bucket.viewer;

import dev.orwell.logging.Logger;
import dev.orwell.undertow.UndertowHttp;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.RequestTooBigException;
import io.undertow.server.RoutingHandler;
import io.undertow.server.handlers.CookieImpl;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A read-only browser over the hub's stored frames: pick a device, see its newest frame, step back.
 *
 * <pre>
 * GET /                              the page, or the sign-in form
 * POST /login, POST /logout          the session
 * GET /viewer.js                     its script
 * GET /api/sources                   devices with frames still stored
 * GET /api/frame?source=&amp;…           one frame plus where it sits — the navigation route
 * GET /api/frames?source=&amp;before=…   a page of frame metadata, newest first
 * GET /api/frames/{id}/image         the frame itself
 * POST /api/uploads?source=&amp;from=&amp;to= a video, cut into frames across that window
 * GET /api/uploads                   recent backfills
 * GET /api/uploads/{id}              one backfill's progress
 * GET /health                        the only route that is never guarded
 * </pre>
 *
 * <p><strong>Metadata and bytes are separate routes, and that is the design.</strong> The hub
 * answers with {@code frameBase64} inline because its caller wants a window in one document. A
 * viewer wants the opposite: navigation has to be cheap enough to hold an arrow key down, and the
 * image has to be cacheable on its own so stepping back and forth over the same few frames does not
 * re-fetch them. So {@code /api/frame} is a few hundred bytes of JSON naming an {@code imageUrl},
 * and the image is immutable and cached for a year.
 *
 * <p>A connection is opened per request and closed after it, as {@code insta ui} does — for
 * something somebody has open in one tab that is cheaper than owning a pool, and it means a database
 * restart costs one failed refresh rather than a permanently broken server.
 *
 * <p><strong>Access is the auth server's decision</strong>, through {@link ViewerAuth}: a browser
 * signs in at {@code POST /login} and carries the resulting token in a session cookie, and a script
 * sends {@code X-Client-Id} and {@code Authorization: Bearer} instead. Both matter here, because the
 * upload route means reaching this port is not only about watching footage but about adding to it.
 *
 * <p>Auth is <em>off</em> when no auth server is configured, and the app says so loudly at startup.
 * That keeps `java -jar` with nothing but a datasource working for local use; the compose service
 * configures one, so the stack is guarded by default. It still binds loopback by default either way
 * — a login is the guard, and the bind address is the backstop.
 */
public final class ViewerServer {
    private static final String PAGE = "/dev/orwell/bucket/viewer/viewer.html";
    private static final String SCRIPT = "/dev/orwell/bucket/viewer/viewer.js";
    private static final String LOGIN = "/dev/orwell/bucket/viewer/login.html";
    private static final HttpString CONTENT_SECURITY_POLICY =
            new HttpString("Content-Security-Policy");
    private static final HttpString X_CONTENT_TYPE_OPTIONS =
            new HttpString("X-Content-Type-Options");

    /**
     * What the page is allowed to do, which is very little.
     *
     * <p>{@code script-src 'self'} is why the script is a separate resource rather than a
     * {@code <script>} block in the page: with no inline script permitted, a payload that somehow
     * reached the browser as markup still could not execute. Frames are stored bytes from whoever
     * can reach the hub's ingest endpoint, so that is a real threat model and not a formality —
     * see {@link ImageMediaType}.
     */
    private static final String CSP = "default-src 'none'; img-src 'self'; script-src 'self'; "
            + "style-src 'self' 'unsafe-inline'; connect-src 'self'; base-uri 'none'; "
            // media-src blob: is for one thing only: the upload panel reads a chosen file's duration
            // through an off-screen <video> on an object URL, to propose a sensible time window. The
            // scheme is script-created and same-origin, so it widens nothing a page could be fed.
            // form-action 'self' rather than 'none': the sign-in form posts back here. It is the
            // only form in the app, and the only reason this is not 'none'.
            + "media-src blob:; form-action 'self'; frame-ancestors 'none'";

    /** How long a sign-in lasts before the form comes back. Eight hours: a working day. */
    private static final int SESSION_SECONDS = 8 * 3600;

    private final ConnectionSource connections;
    private final BackfillService backfill;
    private final ViewerAuth auth;
    private final Logger logger;
    private final int pageSize;
    private final long uploadMaxBytes;
    private final boolean secureCookies;

    public ViewerServer(ConnectionSource connections, BackfillService backfill, ViewerAuth auth,
            int pageSize, long uploadMaxBytes, boolean secureCookies, Logger logger) {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.backfill = Objects.requireNonNull(backfill, "backfill");
        this.auth = Objects.requireNonNull(auth, "auth");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.pageSize = pageSize;
        this.uploadMaxBytes = uploadMaxBytes;
        this.secureCookies = secureCookies;
    }

    /** Opens a fresh database connection. */
    @FunctionalInterface
    public interface ConnectionSource {
        Connection open() throws SQLException;
    }

    /**
     * The routes.
     *
     * <p>Everything but {@code /health} and the sign-in pair is behind {@link #signedIn}. That
     * includes {@code /viewer.js} and the image route: images are requested by the browser as plain
     * same-origin GETs and carry the session cookie like any other request, so guarding them costs
     * nothing and leaving them open would mean the footage itself was never actually protected.
     * {@code /health} stays open because the container's health check reads it and it says nothing
     * about what was recorded.
     */
    public HttpHandler handler() {
        RoutingHandler routes = UndertowHttp.routes();
        routes.get("/", guarded(this::page));
        routes.post("/login", guarded(this::login));
        routes.post("/logout", guarded(this::logout));
        routes.get("/viewer.js", signedIn(exchange ->
                resource(exchange, SCRIPT, "application/javascript; charset=utf-8")));
        routes.get("/api/sources", signedIn(withConnection(this::sources)));
        routes.get("/api/frame", signedIn(withConnection(this::frame)));
        routes.get("/api/frames", signedIn(withConnection(this::frames)));
        routes.get("/api/frames/{id}/image", signedIn(withConnection(this::image)));
        // The backfill routes take no database connection: they push frames to the hub, which is the
        // only thing allowed to write them. See BackfillService.
        routes.post("/api/uploads", signedIn(guarded(this::upload)));
        routes.get("/api/uploads", signedIn(guarded(this::uploads)));
        routes.get("/api/uploads/{id}", signedIn(guarded(this::uploadStatus)));
        routes.get("/health", this::health);
        return routes;
    }

    /**
     * The viewer, or the sign-in form when there is nobody to show it to.
     *
     * <p>Answers the form with 401 rather than a redirect, as {@code object-storage-proxy}'s panel
     * does: there is one page here, and a redirect chain to a second URL buys nothing but a second
     * URL to get wrong.
     */
    private void page(HttpServerExchange exchange) throws IOException {
        if (auth.authenticate(exchange).isPresent()) {
            resource(exchange, PAGE, "text/html; charset=utf-8");
            return;
        }
        loginPage(exchange, 401, "");
    }

    /** Exchanges the form's credentials for an auth-server token, and keeps it in a cookie. */
    private void login(HttpServerExchange exchange) throws IOException {
        if (!auth.enabled()) {
            // Nothing to sign in to. Better than minting a cookie that means nothing.
            UndertowHttp.sendError(exchange, 404, "this viewer has no authentication configured");
            return;
        }
        exchange.startBlocking();
        Map<String, String> form = formBody(exchange);
        String clientId = form.getOrDefault("clientId", "").trim();
        String secret = form.getOrDefault("secret", "");
        if (clientId.isEmpty() || secret.isEmpty()) {
            loginPage(exchange, 400, "Both a client id and a secret are required.");
            return;
        }

        Optional<String> session = auth.login(clientId, secret);
        if (session.isEmpty()) {
            // One message for bad credentials and for an unreachable auth server alike — see
            // ViewerAuth.login for why the difference is not told to an anonymous visitor.
            loginPage(exchange, 401, "That client id and secret were not accepted.");
            return;
        }

        exchange.setResponseCookie(new CookieImpl(ViewerAuth.COOKIE, session.get())
                .setHttpOnly(true)
                .setSecure(secureCookies)
                .setSameSiteMode("Strict")
                .setPath("/")
                .setMaxAge(SESSION_SECONDS));
        // 303, so the browser turns the POST into a GET of the viewer rather than re-posting the
        // credentials if the page is reloaded.
        exchange.setStatusCode(303);
        exchange.getResponseHeaders().put(Headers.LOCATION, "./");
        exchange.endExchange();
    }

    private void logout(HttpServerExchange exchange) throws IOException {
        exchange.setResponseCookie(new CookieImpl(ViewerAuth.COOKIE, "")
                .setHttpOnly(true)
                .setSecure(secureCookies)
                .setSameSiteMode("Strict")
                .setPath("/")
                .setMaxAge(0));
        exchange.setStatusCode(303);
        exchange.getResponseHeaders().put(Headers.LOCATION, "./");
        exchange.endExchange();
    }

    /**
     * Requires a signed-in caller, and answers the same 401 the rest of the repo does.
     *
     * <p>JSON rather than the login page, because everything behind this is fetched by the script:
     * a page of HTML arriving where JSON was expected is the confusing version of this failure. The
     * page watches for the status and sends the visitor back to the sign-in form itself.
     */
    private HttpHandler signedIn(HttpHandler handler) {
        return exchange -> {
            if (auth.authenticate(exchange).isPresent()) {
                handler.handleRequest(exchange);
                return;
            }
            exchange.setStatusCode(401);
            UndertowHttp.sendJson(exchange, 401,
                    Map.of("success", false, "error", "authentication required"));
        };
    }

    private void loginPage(HttpServerExchange exchange, int status, String message)
            throws IOException {
        String page = resourceText(LOGIN);
        if (page == null) {
            UndertowHttp.sendError(exchange, 500, "the viewer is missing from the jar");
            return;
        }
        String rendered = page.replace("%MESSAGE%",
                message.isEmpty() ? "" : "<p class=\"message\">" + escaped(message) + "</p>");
        byte[] body = rendered.getBytes(StandardCharsets.UTF_8);
        var headers = exchange.getResponseHeaders();
        headers.put(Headers.CONTENT_TYPE, "text/html; charset=utf-8");
        headers.put(Headers.CONTENT_LENGTH, Integer.toString(body.length));
        headers.put(CONTENT_SECURITY_POLICY, CSP);
        headers.put(X_CONTENT_TYPE_OPTIONS, "nosniff");
        // A sign-in page must not be served from a cache, least of all a shared one.
        headers.put(Headers.CACHE_CONTROL, "no-store");
        exchange.setStatusCode(status);
        exchange.getResponseSender().send(ByteBuffer.wrap(body));
    }

    /**
     * Parses {@code application/x-www-form-urlencoded}, which is what the sign-in form sends.
     *
     * <p>By hand rather than through Undertow's {@code FormParserFactory}: this is two fields on one
     * route, and the parser wants a handler wrapper and a content-type negotiation to hand back the
     * same two strings.
     */
    private static Map<String, String> formBody(HttpServerExchange exchange) throws IOException {
        // A sign-in form is a few dozen bytes; anything larger is not one.
        exchange.setMaxEntitySize(8 * 1024);
        String body = new String(exchange.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> fields = new LinkedHashMap<>();
        for (String pair : body.split("&")) {
            int equals = pair.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            fields.put(
                    URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
        }
        return fields;
    }

    private static String escaped(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    public void startAndWait(String address, int port) throws InterruptedException {
        if (!auth.enabled()) {
            logger.warn("The frame viewer has no authentication; anything that can reach this port "
                    + "can watch every stored frame"
                    + (backfill.configured() ? " and upload frames of its own." : ".")
                    + " Set FRAME_VIEWER_AUTH_BASE_URL to require a sign-in.",
                    Map.of("address", address, "port", port));
        }
        logger.info("Frame viewer listening.", Map.of(
                "url", "http://" + address + ":" + port,
                "auth", auth.enabled() ? auth.workspace() : "disabled",
                "uploads", backfill.configured() ? backfill.hubUrl() : "disabled"));
        UndertowHttp.startAndWait(address, port, handler());
    }

    /** The devices to choose between, derived from the frames themselves. */
    private void sources(HttpServerExchange exchange, Connection connection) throws Exception {
        List<FrameSource> sources = FrameQueries.sources(connection);
        List<Map<String, Object>> json = new ArrayList<>(sources.size());
        for (FrameSource source : sources) {
            json.add(source.asJson());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("sources", json);
        UndertowHttp.sendJson(exchange, 200, body);
    }

    /**
     * The navigation route: one frame, and the ids either side of it.
     *
     * <p>Four ways to ask, which are the four things the page ever needs:
     * <ul>
     *   <li>nothing but {@code source} — the newest frame, which is where it opens
     *   <li>{@code at=oldest} — the far end of the retained window
     *   <li>{@code before=<id>} / {@code after=<id>} — one step back, one step forward
     *   <li>{@code id=<id>} — an exact frame, so a URL can be shared or reloaded
     * </ul>
     *
     * <p>It answers with {@code olderFrameId} and {@code newerFrameId} rather than leaving the page
     * to discover them: the buttons need to know whether there is anywhere to go, and naming the
     * neighbours lets the page prefetch the next image so a step feels instant. That is five index
     * seeks per navigation instead of one, all on {@code (source, id)}, and it is the difference
     * between a viewer that responds to a held-down arrow key and one that does not.
     */
    private void frame(HttpServerExchange exchange, Connection connection) throws Exception {
        String source = parameter(exchange, "source");
        Long before = longParameter(exchange, "before");
        Long after = longParameter(exchange, "after");
        Long id = longParameter(exchange, "id");
        boolean fromOldest = "oldest".equals(parameter(exchange, "at"));

        Optional<StoredFrame> found;
        if (id != null) {
            found = FrameQueries.byId(connection, id);
        } else if (before != null) {
            found = FrameQueries.older(connection, source, before);
        } else if (after != null) {
            found = FrameQueries.newer(connection, source, after);
        } else if (fromOldest) {
            found = FrameQueries.oldest(connection, source);
        } else {
            found = FrameQueries.latest(connection, source);
        }

        if (found.isEmpty()) {
            // Not an error: stepping past the oldest retained frame is the expected end of the
            // window, and the page uses this to stop rather than to complain.
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", true);
            body.put("frame", null);
            UndertowHttp.sendJson(exchange, 200, body);
            return;
        }

        StoredFrame frame = found.get();
        // Neighbours are scoped to the source the caller is browsing, not to the frame's own — an
        // unscoped viewer steps across every camera in id order, which is what "all sources" means.
        Optional<StoredFrame> older = FrameQueries.older(connection, source, frame.frameId());
        Optional<StoredFrame> newer = FrameQueries.newer(connection, source, frame.frameId());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("frame", frame.asJson());
        body.put("olderFrameId", older.map(StoredFrame::frameId).orElse(null));
        body.put("newerFrameId", newer.map(StoredFrame::frameId).orElse(null));
        body.put("position", FrameQueries.positionOf(connection, source, frame.frameId()));
        body.put("total", FrameQueries.total(connection, source));
        UndertowHttp.sendJson(exchange, 200, body);
    }

    /**
     * A page of frame metadata, newest first — the list, for jumping somewhere rather than stepping
     * there.
     *
     * <p>Capped and paged like the hub's range query, and for a milder version of the same reason:
     * no bytes travel here, so the ceiling protects the response size and the browser's list
     * rendering rather than the heap.
     */
    private void frames(HttpServerExchange exchange, Connection connection) throws Exception {
        String source = parameter(exchange, "source");
        Long before = longParameter(exchange, "before");
        Integer requested = intParameter(exchange, "limit");
        int limit = requested == null ? pageSize : requested;
        if (limit < 1 || limit > pageSize) {
            // Rejected rather than clamped, following FrameQueryService: a caller that asks for more
            // than the cap and silently gets the cap reads the short page as the end of the window.
            UndertowHttp.sendError(exchange, 400, "limit must be between 1 and " + pageSize);
            return;
        }

        // One more than asked for, so "is there another page" costs a row instead of a count.
        List<StoredFrame> found = FrameQueries.page(connection, source, before, limit + 1);
        boolean hasMore = found.size() > limit;
        List<StoredFrame> page = hasMore ? found.subList(0, limit) : found;

        List<Map<String, Object>> frames = new ArrayList<>(page.size());
        for (StoredFrame frame : page) {
            frames.add(frame.asJson());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("source", source);
        body.put("before", before);
        body.put("limit", limit);
        body.put("returned", frames.size());
        body.put("hasMore", hasMore);
        body.put("nextBefore", hasMore ? page.get(page.size() - 1).frameId() : null);
        body.put("frames", frames);
        UndertowHttp.sendJson(exchange, 200, body);
    }

    /**
     * The frame itself, as an image the browser renders and caches.
     *
     * <p>Cached hard on purpose: a frame id comes from a sequence and its bytes never change, so
     * {@code immutable} is true in the strict sense the header means it, and it is what makes
     * stepping back and forth over the same stretch of history free after the first pass.
     * {@code private} rather than {@code public} because the payload is camera footage and a shared
     * cache has no business holding it.
     *
     * <p>{@code If-None-Match} is answered before the query runs, so a revalidating client costs no
     * database read at all.
     */
    private void image(HttpServerExchange exchange, Connection connection) throws Exception {
        Long frameId = longParameter(exchange, "id");
        if (frameId == null) {
            UndertowHttp.sendError(exchange, 400, "id must be a frame id");
            return;
        }

        String etag = "\"frame-" + frameId + "\"";
        if (etag.equals(exchange.getRequestHeaders().getFirst(Headers.IF_NONE_MATCH))) {
            exchange.setStatusCode(304);
            exchange.getResponseHeaders().put(Headers.ETAG, etag);
            exchange.endExchange();
            return;
        }

        Optional<byte[]> found = FrameQueries.imageBytes(connection, frameId);
        if (found.isEmpty()) {
            // Either it never existed or retention has since deleted it. The page treats both the
            // same way, and there is nothing useful to tell them apart with.
            UndertowHttp.sendError(exchange, 404, "no such frame");
            return;
        }

        byte[] payload = found.get();
        String mediaType = ImageMediaType.of(payload);
        var headers = exchange.getResponseHeaders();
        headers.put(Headers.CONTENT_TYPE, mediaType);
        headers.put(Headers.CONTENT_LENGTH, Integer.toString(payload.length));
        headers.put(Headers.CACHE_CONTROL, "private, max-age=31536000, immutable");
        headers.put(Headers.ETAG, etag);
        // Belt and braces around the sniffing in ImageMediaType: the browser must not reinterpret
        // these bytes as anything other than what was decided here.
        headers.put(X_CONTENT_TYPE_OPTIONS, "nosniff");
        if (!ImageMediaType.isImage(mediaType)) {
            // Not a recognised image, so it is downloaded rather than rendered. The hub stores
            // whatever a producer pushes and never decodes it, so this is reachable.
            headers.put(Headers.CONTENT_DISPOSITION,
                    "attachment; filename=\"frame-" + frameId + ".bin\"");
        }
        exchange.setStatusCode(200);
        exchange.getResponseSender().send(ByteBuffer.wrap(payload));
    }

    /**
     * Accepts a video and backfills it as frames dated across {@code [from, to)}.
     *
     * <p><strong>The body is the video, raw.</strong> Not multipart: the metadata is four scalars that
     * belong in the query string, and multipart would buy a parser, a boundary and a temp-file
     * lifecycle to carry the same four values alongside bytes that are already the whole body. Raw
     * also means the obvious thing works —
     * {@code curl --data-binary @clip.mp4 '…/api/uploads?source=cam1&from=…&to=…'} — and the browser
     * sends the {@code File} straight from the picker with no encoding step.
     *
     * <p>It answers 202 and a job to poll, because cutting and pushing a video takes minutes.
     * {@code from} and {@code to} are required and there is no default: the entire point of the route
     * is that the caller knows when this footage was recorded, and guessing "ending now" for them
     * would silently mis-date an archive.
     *
     * <p>The upload streams to a temp file and never into the heap — a 512 MB container cannot hold a
     * 512 MB video, and this app is not sized for one video, let alone two at once.
     */
    private void upload(HttpServerExchange exchange) throws IOException {
        if (!backfill.configured()) {
            UndertowHttp.sendError(exchange, 503,
                    "no hub is configured; set FRAME_VIEWER_HUB_URL to enable uploads");
            return;
        }

        String source = required(exchange, "source");
        Instant from = requiredInstant(exchange, "from");
        Instant to = requiredInstant(exchange, "to");
        if (!from.isBefore(to)) {
            // Equal bounds are rejected too: with a half-open window they would describe no time at
            // all, and every frame would land on the same instant.
            throw new IllegalArgumentException("from must be before to");
        }
        Double fps = doubleParameter(exchange, "fps");
        if (fps != null && (fps <= 0 || fps > 60)) {
            throw new IllegalArgumentException("fps must be greater than 0 and at most 60");
        }

        exchange.setMaxEntitySize(uploadMaxBytes);
        exchange.startBlocking();
        Path video = Files.createTempFile("frame-upload-", ".video");
        long bytes;
        try (InputStream uploaded = exchange.getInputStream()) {
            bytes = Files.copy(uploaded, video, StandardCopyOption.REPLACE_EXISTING);
        } catch (RequestTooBigException exception) {
            Files.deleteIfExists(video);
            UndertowHttp.sendError(exchange, 413,
                    "that video is larger than FRAME_VIEWER_UPLOAD_MAX_BYTES (" + uploadMaxBytes + ")");
            return;
        } catch (IOException | RuntimeException exception) {
            Files.deleteIfExists(video);
            throw exception;
        }
        if (bytes == 0) {
            Files.deleteIfExists(video);
            throw new IllegalArgumentException("the request body is the video, and it was empty");
        }

        // The service owns the file from here, including deleting it.
        UploadJob job = backfill.accept(video, source, from, to, fps, bytes);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("upload", job.asJson());
        body.put("statusUrl", "api/uploads/" + job.id());
        UndertowHttp.sendJson(exchange, 202, body);
    }

    /** Recent backfills, newest first — enough to see whether the last one worked. */
    private void uploads(HttpServerExchange exchange) throws IOException {
        List<Map<String, Object>> uploads = new ArrayList<>();
        for (UploadJob job : backfill.recentJobs()) {
            uploads.add(job.asJson());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("uploadsEnabled", backfill.configured());
        body.put("defaultFps", backfill.defaultFramesPerSecond());
        body.put("uploads", uploads);
        UndertowHttp.sendJson(exchange, 200, body);
    }

    private void uploadStatus(HttpServerExchange exchange) throws IOException {
        String id = required(exchange, "id");
        Optional<UploadJob> job = backfill.job(id);
        if (job.isEmpty()) {
            // Either it never existed or it is old enough to have been forgotten; a caller polling a
            // job it just started cannot hit the second case.
            UndertowHttp.sendError(exchange, 404, "no such upload");
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("upload", job.get().asJson());
        UndertowHttp.sendJson(exchange, 200, body);
    }

    /**
     * Health, which for this app means the database.
     *
     * <p>It reports unhealthy with a 503 when the table cannot be read, rather than answering
     * {@code healthy} with the detail buried in a field: everything this process does is read that
     * table, so a container orchestrator being told "up" while it is unreachable is a health check
     * that does nothing.
     */
    private void health(HttpServerExchange exchange) throws IOException {
        try (Connection connection = connections.open()) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("app", "jarvis-frame-viewer");
            details.put("storedFrames", FrameQueries.storedFrames(connection));
            details.put("sources", FrameQueries.sources(connection).size());
            // Whether the backfill route will do anything, which is the one bit of this app's
            // configuration that is not visible from its behaviour until somebody tries an upload.
            details.put("uploadsEnabled", backfill.configured());
            // Whether anything is guarding this port, which is the one thing an operator most wants
            // to be able to check from outside without having to read the startup log.
            details.put("authRequired", auth.enabled());
            UndertowHttp.sendJson(exchange, 200, UndertowHttp.health(details));
        } catch (SQLException exception) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", false);
            body.put("status", "unhealthy");
            body.put("app", "jarvis-frame-viewer");
            body.put("error", "cannot reach frame_events");
            body.put("engine", "undertow");
            logger.error("The frame viewer cannot reach the database.",
                    Map.of("error", exception.toString()));
            UndertowHttp.sendJson(exchange, 503, body);
        }
    }

    /** A classpath resource as text, or null when it is missing from the jar. */
    private static String resourceText(String path) throws IOException {
        try (InputStream stream = ViewerServer.class.getResourceAsStream(path)) {
            return stream == null ? null : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void resource(HttpServerExchange exchange, String path, String contentType)
            throws IOException {
        try (InputStream stream = ViewerServer.class.getResourceAsStream(path)) {
            if (stream == null) {
                UndertowHttp.sendError(exchange, 500, "the viewer is missing from the jar");
                return;
            }
            byte[] body = stream.readAllBytes();
            var headers = exchange.getResponseHeaders();
            headers.put(Headers.CONTENT_TYPE, contentType);
            headers.put(Headers.CONTENT_LENGTH, Integer.toString(body.length));
            headers.put(CONTENT_SECURITY_POLICY, CSP);
            headers.put(X_CONTENT_TYPE_OPTIONS, "nosniff");
            exchange.getResponseSender().send(ByteBuffer.wrap(body));
        }
    }

    /**
     * Wraps a handler with a connection it owns. A database that is down becomes a 503 on one
     * request rather than an exception that takes the listener with it.
     */
    private HttpHandler withConnection(DatabaseHandler handler) {
        return exchange -> {
            try (Connection connection = connections.open()) {
                handler.handle(exchange, connection);
            } catch (IllegalArgumentException exception) {
                UndertowHttp.sendError(exchange, 400, exception.getMessage());
            } catch (Exception exception) {
                logger.error("A viewer request failed.", Map.of(
                        "path", exchange.getRequestPath(), "error", exception.toString()));
                UndertowHttp.sendError(exchange, 503, "could not read the stored frames");
            }
        };
    }

    @FunctionalInterface
    private interface DatabaseHandler {
        void handle(HttpServerExchange exchange, Connection connection) throws Exception;
    }

    /**
     * The same error contract as {@link #withConnection}, for the routes that touch no database.
     *
     * <p>Kept separate rather than folded into that method: opening a connection for the backfill
     * routes would be a connection they never use, and it would make a database outage break uploads
     * that go nowhere near it.
     */
    private HttpHandler guarded(HttpHandler handler) {
        return exchange -> {
            try {
                handler.handleRequest(exchange);
            } catch (IllegalArgumentException exception) {
                UndertowHttp.sendError(exchange, 400, exception.getMessage());
            } catch (Exception exception) {
                logger.error("A viewer request failed.", Map.of(
                        "path", exchange.getRequestPath(), "error", exception.toString()));
                UndertowHttp.sendError(exchange, 500, "the request failed");
            }
        };
    }

    /**
     * A query parameter, trimmed, or null when absent or blank.
     *
     * <p>Undertow's routing handler puts path template variables in here too, which is where the
     * {@code id} of {@code /api/frames/{id}/image} comes from.
     */
    private static String parameter(HttpServerExchange exchange, String name) {
        var values = exchange.getQueryParameters().get(name);
        if (values == null || values.isEmpty()) {
            return null;
        }
        String value = values.getFirst();
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Long longParameter(HttpServerExchange exchange, String name) {
        String value = parameter(exchange, name);
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException exception) {
            // Caught by withConnection and answered as a 400 naming the parameter, so a mistyped
            // URL reads like every other bad request rather than as a server fault.
            throw new IllegalArgumentException(name + " must be a number");
        }
    }

    /** A parameter the route cannot work without, as a 400 rather than a null further down. */
    private static String required(HttpServerExchange exchange, String name) {
        String value = parameter(exchange, name);
        if (value == null) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    /**
     * A required ISO-8601 instant.
     *
     * <p>Parsed here so the 400 names the parameter and matches every other error body this app and
     * the hub give — the hub's {@code HubEndpoint} parses its own timestamps for the same reason.
     */
    private static Instant requiredInstant(HttpServerExchange exchange, String name) {
        String value = required(exchange, name);
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException(
                    name + " must be an ISO-8601 instant, like 2026-08-17T10:00:00Z");
        }
    }

    private static Double doubleParameter(HttpServerExchange exchange, String name) {
        String value = parameter(exchange, name);
        if (value == null) {
            return null;
        }
        try {
            return Double.valueOf(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be a number");
        }
    }

    private static Integer intParameter(HttpServerExchange exchange, String name) {
        Long value = longParameter(exchange, name);
        if (value == null) {
            return null;
        }
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(name + " must be a number");
        }
        return value.intValue();
    }
}
