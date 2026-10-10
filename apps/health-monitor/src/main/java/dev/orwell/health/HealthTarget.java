package dev.orwell.health;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One endpoint to check, and the name it is called by.
 *
 * <p>Configured as a comma-separated {@code name=url} list rather than as JSON, unlike
 * {@code jarvis-fixture-detection}'s fixtures: a target is two strings with no structure, and
 * {@code auth=http://auth-server/auth/health,hub=http://jarvis-hub:9001/health} reads perfectly well
 * in a compose file where a JSON blob would not. The moment a target grows a third field this should
 * become a file.
 *
 * <p>The name is what appears in the alert and in {@code /targets}, so it should be the service's
 * name rather than its address — an address is a fact about today's deployment, and an alert saying
 * {@code 172.20.0.7 is down} at three in the morning is a puzzle rather than a message.
 */
public record HealthTarget(String name, URI url) {

    public HealthTarget {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(url, "url");
        if (name.isBlank()) {
            throw new IllegalArgumentException("a target needs a name");
        }
    }

    /**
     * Parses the {@code HEALTH_MONITOR_TARGETS} list.
     *
     * <p>Everything wrong here is fatal at startup, deliberately. A monitor with an unparseable
     * target list watches less than it was asked to and reports nothing about the difference — and
     * "no alerts" is exactly what a healthy stack looks like, so there is no symptom to notice.
     *
     * @throws IllegalArgumentException naming the entry it could not read.
     */
    public static List<HealthTarget> parse(String configuration) {
        if (configuration == null || configuration.isBlank()) {
            // Not an error: a monitor with nothing configured is what a fresh deployment looks
            // like. It warns at startup instead.
            return List.of();
        }

        List<HealthTarget> targets = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        for (String entry : configuration.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            // The first '=' only: a URL may contain one in a query string, and splitting on all of
            // them would quietly truncate it.
            int equals = trimmed.indexOf('=');
            if (equals <= 0 || equals == trimmed.length() - 1) {
                throw new IllegalArgumentException(
                        "'" + trimmed + "' is not a target; write it as name=url");
            }
            String name = trimmed.substring(0, equals).trim();
            String url = trimmed.substring(equals + 1).trim();
            if (!names.add(name)) {
                // Names key /targets and the alerts; two the same makes both ambiguous.
                throw new IllegalArgumentException("two targets are both called '" + name + "'");
            }
            targets.add(new HealthTarget(name, uri(name, url)));
        }
        return List.copyOf(targets);
    }

    private static URI uri(String name, String url) {
        URI parsed;
        try {
            parsed = URI.create(url);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "target '" + name + "' has an unusable url: " + exception.getMessage());
        }
        // Checked rather than assumed: a relative URI reaches HttpClient as an
        // IllegalArgumentException per request, which would read as every check failing rather than
        // as a typo in one setting.
        if (parsed.getScheme() == null || parsed.getHost() == null) {
            throw new IllegalArgumentException("target '" + name
                    + "' needs an absolute url with a scheme and host, got '" + url + "'");
        }
        return parsed;
    }

    public Map<String, Object> asJson() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("name", name);
        json.put("url", url.toString());
        return json;
    }
}
