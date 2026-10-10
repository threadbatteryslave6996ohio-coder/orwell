package dev.orwell.insta.scraper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.orwell.http.LoggingHttpClient;
import dev.orwell.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Instagram's private web API, which is the only thing that answers "who follows this account".
 *
 * <p>Three calls, all GET:
 * <pre>
 * /api/v1/users/web_profile_info/?username=nasa      → data.user.id
 * /api/v1/friendships/{id}/followers/?count=50&amp;max_id=…  → {users:[…], next_max_id:…}
 * /api/v1/friendships/{id}/following/?count=50&amp;max_id=…
 * </pre>
 *
 * <p>{@code next_max_id} is present exactly when more pages exist, which is what lets the service
 * above hand insta an honest cursor: absent means the list really is exhausted, and insta may
 * retire on it.
 *
 * <p><b>Every failure mode here has to stay distinguishable from an empty list.</b> A checkpoint
 * page, an expired session and a throttle all come back as something that is not a users array,
 * and returning zero accounts for any of them would read, three services later, as every follower
 * leaving at once. So anything unrecognised is a {@link ScrapeException} with a status for the
 * endpoint to pass on, never an empty result.
 */
public final class InstagramWebClient {
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http;
    private final String baseUrl;
    private final InstagramSession session;
    private final RequestBudget budget;
    private final int pageSize;
    private final Logger logger;

    public InstagramWebClient(
            String baseUrl, InstagramSession session, RequestBudget budget, int pageSize,
            Logger logger) {
        this.baseUrl = stripTrailingSlash(Objects.requireNonNull(baseUrl, "baseUrl"));
        this.session = Objects.requireNonNull(session, "session");
        this.budget = Objects.requireNonNull(budget, "budget");
        this.pageSize = Math.max(pageSize, 1);
        this.logger = Objects.requireNonNull(logger, "logger");
        this.http = LoggingHttpClient.wrap(
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        // Instagram answers a dead session with a 302 to /accounts/login/. Followed,
                        // that becomes a 200 of HTML and looks like a parse problem; unfollowed it
                        // stays the 302 it is, and reads as the expired session it is.
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                logger);
    }

    /** Instagram's numeric id for a handle. */
    public String userId(String username) throws ScrapeException, InterruptedException {
        JsonNode body = get(baseUrl + "/api/v1/users/web_profile_info/?username="
                + URLEncoder.encode(username, StandardCharsets.UTF_8));
        JsonNode user = body.path("data").path("user");
        String id = user.path("id").asText(null);
        if (id == null || id.isBlank()) {
            // A handle that does not exist answers 404, so reaching here means the shape moved.
            throw new ScrapeException(
                    "No user id in web_profile_info for " + username, 502);
        }
        return id;
    }

    /**
     * One page of a connection list.
     *
     * @param maxId the previous page's cursor, or null to start
     */
    public Page connections(String userId, Direction direction, String maxId)
            throws ScrapeException, InterruptedException {

        StringBuilder url = new StringBuilder(baseUrl)
                .append("/api/v1/friendships/").append(URLEncoder.encode(userId, StandardCharsets.UTF_8))
                .append("/").append(direction.path()).append("/?count=").append(pageSize);
        if (maxId != null && !maxId.isBlank()) {
            url.append("&max_id=").append(URLEncoder.encode(maxId, StandardCharsets.UTF_8));
        }

        JsonNode body = get(url.toString());
        JsonNode users = body.get("users");
        if (users == null || !users.isArray()) {
            // The important branch. Never an empty page — see the class comment.
            throw new ScrapeException("No users array in the friendships response.", 502);
        }
        List<Account> accounts = new ArrayList<>(users.size());
        for (JsonNode user : users) {
            Account account = Account.from(user);
            if (account != null) {
                accounts.add(account);
            }
        }
        String next = body.path("next_max_id").asText(null);
        return new Page(accounts, next == null || next.isBlank() ? null : next);
    }

    private JsonNode get(String url) throws ScrapeException, InterruptedException {
        try {
            budget.take(Instant.now());
        } catch (RequestBudget.ExhaustedException exhausted) {
            throw new ScrapeException(exhausted.getMessage(), 429, exhausted.retryAfterSeconds());
        }

        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .GET();
        session.headers().forEach(request::header);

        HttpResponse<String> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException exception) {
            throw new ScrapeException("Instagram could not be reached: " + exception, 502);
        }

        int code = response.statusCode();
        if (code == 302 || code == 401 || code == 403) {
            // The session is gone, or this account has been checkpointed. 401 upward, so insta
            // fails over rather than treating it as a lookup that legitimately found nothing.
            logger.warn("The Instagram session was refused.", Map.of(
                    "status", code, "url", pathOf(url)));
            throw new ScrapeException(
                    "The Instagram session was refused (HTTP " + code + "); it may have expired.",
                    401);
        }
        if (code == 429) {
            logger.warn("Instagram is throttling this session.", Map.of("url", pathOf(url)));
            throw new ScrapeException("Instagram is throttling this session.", 429, 900);
        }
        if (code == 404) {
            throw new ScrapeException("No such account.", 404);
        }
        if (code / 100 != 2) {
            throw new ScrapeException("Instagram answered HTTP " + code + ".", 502);
        }
        try {
            return json.readTree(response.body());
        } catch (Exception exception) {
            // Almost always a checkpoint or consent interstitial served as HTML.
            throw new ScrapeException(
                    "Instagram answered with something that is not JSON; the session is probably "
                            + "checkpointed.", 401);
        }
    }

    /** Which list. The value is the path segment Instagram uses. */
    public enum Direction {
        FOLLOWERS("followers"), FOLLOWING("following");

        private final String path;

        Direction(String path) {
            this.path = path;
        }

        String path() {
            return path;
        }

        /** @return null for anything that is not one of the two. */
        public static Direction parse(String raw) {
            if (raw == null) {
                return null;
            }
            return switch (raw.toLowerCase(java.util.Locale.ROOT)) {
                case "followers" -> FOLLOWERS;
                case "following" -> FOLLOWING;
                default -> null;
            };
        }
    }

    /** @param nextMaxId null when Instagram reported no more pages — the end of the list. */
    public record Page(List<Account> accounts, String nextMaxId) {
    }

    /**
     * One account, in the shape insta's {@code self-hosted} source documents.
     *
     * <p>{@code id} is filled from {@code pk}, and getting it is not optional in practice: insta
     * cannot store a row it cannot key, and any unkeyable row blocks unfollow detection for the
     * whole walk.
     */
    public record Account(
            String id, String username, String fullName, Boolean isPrivate, Boolean isVerified,
            String profilePicUrl) {

        static Account from(JsonNode user) {
            String username = text(user, "username");
            if (username == null || username.isBlank()) {
                return null;
            }
            String id = text(user, "pk");
            if (id == null) {
                id = text(user, "pk_id");
            }
            if (id == null) {
                id = text(user, "id");
            }
            return new Account(
                    id, username, text(user, "full_name"),
                    bool(user, "is_private"), bool(user, "is_verified"),
                    text(user, "profile_pic_url"));
        }

        private static String text(JsonNode node, String field) {
            JsonNode value = node.get(field);
            return value == null || value.isNull() || !value.isValueNode() ? null : value.asText();
        }

        private static Boolean bool(JsonNode node, String field) {
            JsonNode value = node.get(field);
            return value == null || !value.isBoolean() ? null : value.asBoolean();
        }
    }

    /** A failure, carrying the HTTP status the endpoint should answer with. */
    public static final class ScrapeException extends Exception {
        private final int status;
        private final long retryAfterSeconds;

        public ScrapeException(String message, int status) {
            this(message, status, 0);
        }

        public ScrapeException(String message, int status, long retryAfterSeconds) {
            super(message);
            this.status = status;
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public int status() {
            return status;
        }

        public long retryAfterSeconds() {
            return retryAfterSeconds;
        }
    }

    private static String pathOf(String url) {
        return URI.create(url).getPath();
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
