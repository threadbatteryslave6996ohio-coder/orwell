package dev.orwell.insta.scraper;

import dev.orwell.env.Env;
import dev.orwell.logging.Logger;
import dev.orwell.logging.LoggerSetup;
import dev.orwell.undertow.UndertowHttp;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The service behind insta's {@code self-hosted} connections source.
 *
 * <p>Two routes and a cookie jar. It exists rather than being a source inside insta for one
 * reason: insta is a CLI run from cron, so it cannot remember it made four hundred Instagram
 * requests forty minutes ago, and a scraper that cannot remember that gets its account banned.
 * A process that stays up can — see {@link RequestBudget}.
 *
 * <p>It keeps Instagram credentials out of insta as a side effect, which is worth having: insta
 * holds no session and never has, and the only other thing that would change that is the
 * {@code logical-scrapers} adapter, which is opt-in for the same reason.
 *
 * <p><b>This will get rate-limited and eventually flagged.</b> That is inherent, not a bug to fix
 * here — it is exactly what the paid actors charge for absorbing. The design assumption is that
 * insta has a paid source behind this one in its chain, so a refusal costs money rather than
 * costing a walk.
 */
public final class InstaScraperApplication {
    /** The logger name and the Loki {@code app} label; matches the artifactId. */
    private static final String APP_NAME = "insta-scraper";

    public static void main(String[] args) {
        Env env;
        try {
            env = InstaScraperEnvs.load();
        } catch (Exception exception) {
            // Before the logger exists there is nowhere else for this to go. A missing
            // INSTA_SCRAPER_SESSIONID lands here, which is deliberate: a scraper with no session
            // that starts and looks healthy would make insta pay a paid actor for every walk.
            System.err.println("insta-scraper: " + exception.getMessage());
            System.exit(1);
            return;
        }

        Logger logger = LoggerSetup.fromConfiguration(
                APP_NAME,
                env.get(InstaScraperEnvs.LOGGER),
                env.get(InstaScraperEnvs.LOKI_URL),
                env.get(InstaScraperEnvs.LOKI_TENANT_ID));

        InstagramSession session = InstagramSession.from(env);
        RequestBudget budget = new RequestBudget(
                env.get(InstaScraperEnvs.INSTA_SCRAPER_MIN_INTERVAL_MILLIS),
                env.get(InstaScraperEnvs.INSTA_SCRAPER_HOURLY_REQUESTS));
        InstagramWebClient instagram = new InstagramWebClient(
                env.get(InstaScraperEnvs.INSTA_SCRAPER_BASE_URL),
                session, budget,
                env.get(InstaScraperEnvs.INSTA_SCRAPER_PAGE_SIZE),
                logger);

        String token = env.get(InstaScraperEnvs.INSTA_SCRAPER_TOKEN);
        String address = env.get(InstaScraperEnvs.SERVER_ADDRESS);
        int port = env.get(InstaScraperEnvs.SERVER_PORT);
        if (token == null || token.isBlank()) {
            // Same warning insta's viewer gives, for the same reason: this endpoint hands out
            // follower graphs to anything that can reach the port.
            logger.warn("No INSTA_SCRAPER_TOKEN is set; this endpoint is unauthenticated.",
                    Map.of("address", address, "port", port));
        }
        logger.info("Instagram session loaded.", Map.of("session", session.describe()));

        var routes = UndertowHttp.routes()
                .get("/connections", new ConnectionsEndpoint(
                        instagram, token,
                        Duration.ofSeconds(
                                env.get(InstaScraperEnvs.INSTA_SCRAPER_MAX_SECONDS_PER_REQUEST)),
                        logger))
                .get("/health", exchange ->
                        UndertowHttp.sendJson(exchange, 200, UndertowHttp.health(health(budget))));

        try {
            UndertowHttp.startAndWait(address, port, routes);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Reports the budget rather than merely that the port is open. A scraper whose hourly cap is
     * spent is up and answering 429 to everything, and "running" and "working" should not look
     * identical from here.
     */
    private static Map<String, Object> health(RequestBudget budget) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("requestsThisHour", budget.usedThisHour());
        details.put("hourlyLimit", budget.hourlyLimit());
        return details;
    }

    private InstaScraperApplication() {
    }
}
