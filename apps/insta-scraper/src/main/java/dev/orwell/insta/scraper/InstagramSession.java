package dev.orwell.insta.scraper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The logged-in session, as a set of headers.
 *
 * <p>There is no API key to apply for here — Instagram's Graph API does not expose follower lists
 * to anyone — so this drives the same private web endpoints a browser does, which means being
 * logged in as somebody. {@code sessionid} <em>is</em> that login: whoever holds it is signed in as
 * that account until it is revoked.
 *
 * <p>The rest is about not looking like a robot. {@code X-IG-App-ID} is required outright (without
 * it the endpoints answer 400). The user agent has to match the browser the cookies were issued
 * to, and the device cookies have to stay stable between requests — a session whose device changes
 * every call is the pattern fraud detection is looking for.
 *
 * <p>Deliberately never logged. {@link #describe()} exists so startup can say what it has without
 * putting a live session in a log line.
 */
public final class InstagramSession {
    private final String cookieHeader;
    private final String userAgent;
    private final String appId;
    private final String csrfToken;

    public InstagramSession(
            String sessionId, String dsUserId, String csrfToken, String extraCookies,
            String userAgent, String appId) {

        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("INSTA_SCRAPER_SESSIONID is required.");
        }
        this.userAgent = Objects.requireNonNull(userAgent, "userAgent");
        this.appId = Objects.requireNonNull(appId, "appId");
        this.csrfToken = csrfToken == null ? "" : csrfToken.trim();

        StringBuilder cookies = new StringBuilder("sessionid=").append(sessionId.trim());
        append(cookies, "ds_user_id", dsUserId);
        append(cookies, "csrftoken", this.csrfToken);
        if (extraCookies != null && !extraCookies.isBlank()) {
            String extra = extraCookies.trim();
            cookies.append("; ").append(extra.endsWith(";")
                    ? extra.substring(0, extra.length() - 1)
                    : extra);
        }
        this.cookieHeader = cookies.toString();
    }

    public static InstagramSession from(dev.orwell.env.Env env) {
        return new InstagramSession(
                env.get(InstaScraperEnvs.INSTA_SCRAPER_SESSIONID),
                env.get(InstaScraperEnvs.INSTA_SCRAPER_DS_USER_ID),
                env.get(InstaScraperEnvs.INSTA_SCRAPER_CSRF_TOKEN),
                env.get(InstaScraperEnvs.INSTA_SCRAPER_EXTRA_COOKIES),
                env.get(InstaScraperEnvs.INSTA_SCRAPER_USER_AGENT),
                env.get(InstaScraperEnvs.INSTA_SCRAPER_APP_ID));
    }

    /** Headers for one request. A fresh map each time, so a caller cannot mutate the session. */
    public Map<String, String> headers() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Cookie", cookieHeader);
        headers.put("User-Agent", userAgent);
        headers.put("X-IG-App-ID", appId);
        headers.put("Accept", "application/json");
        headers.put("Referer", "https://www.instagram.com/");
        // Sent when we have one: read endpoints tolerate its absence, and the ones that do not
        // answer 403 rather than failing in a way worth guessing about.
        if (!csrfToken.isBlank()) {
            headers.put("X-CSRFToken", csrfToken);
        }
        return headers;
    }

    /** Safe to log: says what is configured without revealing any of it. */
    public String describe() {
        return "sessionid set, %d cookies, app id %s".formatted(
                cookieHeader.split(";").length, appId);
    }

    private static void append(StringBuilder cookies, String name, String value) {
        if (value != null && !value.isBlank()) {
            cookies.append("; ").append(name).append("=").append(value.trim());
        }
    }
}
