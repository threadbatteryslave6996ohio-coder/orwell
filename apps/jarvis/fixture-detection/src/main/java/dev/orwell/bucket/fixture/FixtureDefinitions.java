package dev.orwell.bucket.fixture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads the fixture list, and refuses to start on a bad one.
 *
 * <p>Configuration is JSON rather than environment variables because a fixture is nested and there
 * are several of them: {@code FIXTURE_LAMP_REGION_X} × four corners × two regions × N fixtures is a
 * key space nobody can hold in their head, and the region's four numbers only mean anything
 * together. It is read from a file when {@code FIXTURE_DETECTION_DEFINITIONS_FILE} names one, so it
 * can be mounted and edited without rebuilding, and inline from
 * {@code FIXTURE_DETECTION_DEFINITIONS} otherwise.
 *
 * <p><strong>Every problem here is fatal at startup.</strong> A fixture with an unparseable region or
 * inverted thresholds cannot detect anything, and a detector that starts anyway and quietly watches
 * nothing is the failure this is written to avoid — it looks healthy, reports no state changes, and
 * "no state changes" is exactly what a working lamp detector looks like most of the time.
 *
 * <pre>{@code
 * [
 *   {
 *     "name": "living-room-lamp",
 *     "source": "cam1",
 *     "region":    {"x": 0.62, "y": 0.18, "width": 0.10, "height": 0.12},
 *     "reference": {"x": 0.20, "y": 0.18, "width": 0.10, "height": 0.12},
 *     "metric": "MEAN_LUMINANCE",
 *     "onAbove": 22, "offBelow": 10, "confirmFrames": 3
 *   },
 *   {
 *     "name": "stove-burner",
 *     "region": {"x": 0.40, "y": 0.70, "width": 0.14, "height": 0.10},
 *     "metric": "SATURATED_BRIGHT_FRACTION",
 *     "brightCutoff": 110, "saturationCutoff": 0.35,
 *     "onAbove": 0.02, "offBelow": 0.005, "confirmFrames": 2
 *   }
 * ]
 * }</pre>
 */
public final class FixtureDefinitions {
    /** Luma at or above which a pixel counts as bright, when a fixture does not say. */
    static final int DEFAULT_BRIGHT_CUTOFF = 200;
    /** Saturation at or above which a pixel counts as coloured, when a fixture does not say. */
    static final double DEFAULT_SATURATION_CUTOFF = 0.30;
    /** Frames that must agree before a change is reported — three seconds at 1 fps. */
    static final int DEFAULT_CONFIRM_FRAMES = 3;

    private static final ObjectMapper JSON = new ObjectMapper();

    private FixtureDefinitions() {
    }

    /**
     * The fixtures to watch, from a file if one is named and from the inline value otherwise.
     *
     * @throws IllegalArgumentException on anything unusable, naming the fixture and the field.
     */
    public static List<Fixture> load(String inlineJson, String filePath) {
        String json = inlineJson;
        if (filePath != null && !filePath.isBlank()) {
            try {
                json = Files.readString(Path.of(filePath.trim()));
            } catch (IOException exception) {
                throw new IllegalArgumentException(
                        "cannot read FIXTURE_DETECTION_DEFINITIONS_FILE " + filePath + ": "
                                + exception.getMessage());
            }
        }
        return parse(json);
    }

    static List<Fixture> parse(String json) {
        if (json == null || json.isBlank()) {
            // Not an error: a detector with nothing configured is what a fresh deployment looks
            // like, and it says so at startup rather than refusing to run.
            return List.of();
        }
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (IOException exception) {
            throw new IllegalArgumentException(
                    "the fixture definitions are not valid JSON: " + exception.getMessage());
        }
        if (!root.isArray()) {
            throw new IllegalArgumentException(
                    "the fixture definitions must be a JSON array of fixtures");
        }

        List<Fixture> fixtures = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (int index = 0; index < root.size(); index++) {
            Fixture fixture = fixtureFrom(root.get(index), index);
            // Names are the key in /fixtures, in the preview URL, in the log and in the alert. Two
            // fixtures sharing one would make every one of those ambiguous.
            if (!names.add(fixture.name())) {
                throw new IllegalArgumentException(
                        "two fixtures are both called '" + fixture.name() + "'");
            }
            fixtures.add(fixture);
        }
        return List.copyOf(fixtures);
    }

    private static Fixture fixtureFrom(JsonNode node, int index) {
        if (!node.isObject()) {
            throw new IllegalArgumentException("fixture " + index + " is not a JSON object");
        }
        String name = text(node, "name", "fixture " + index);
        String where = "fixture '" + name + "'";
        return new Fixture(
                name,
                node.hasNonNull("source") ? node.get("source").asText() : null,
                regionFrom(node.get("region"), where + " region"),
                node.hasNonNull("reference")
                        ? regionFrom(node.get("reference"), where + " reference")
                        : null,
                metricFrom(node, where),
                node.hasNonNull("brightCutoff")
                        ? node.get("brightCutoff").asInt()
                        : DEFAULT_BRIGHT_CUTOFF,
                node.hasNonNull("saturationCutoff")
                        ? node.get("saturationCutoff").asDouble()
                        : DEFAULT_SATURATION_CUTOFF,
                number(node, "onAbove", where),
                number(node, "offBelow", where),
                node.hasNonNull("confirmFrames")
                        ? node.get("confirmFrames").asInt()
                        : DEFAULT_CONFIRM_FRAMES);
    }

    private static Region regionFrom(JsonNode node, String where) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException(
                    where + " must be an object with x, y, width and height");
        }
        try {
            return new Region(
                    number(node, "x", where),
                    number(node, "y", where),
                    number(node, "width", where),
                    number(node, "height", where));
        } catch (IllegalArgumentException exception) {
            // Region validates itself; prefix the message so the complaint names which region.
            throw new IllegalArgumentException(where + ": " + exception.getMessage());
        }
    }

    private static FixtureMetric metricFrom(JsonNode node, String where) {
        if (!node.hasNonNull("metric")) {
            return FixtureMetric.MEAN_LUMINANCE;
        }
        String metric = node.get("metric").asText().trim().toUpperCase();
        try {
            return FixtureMetric.valueOf(metric);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(where + ": '" + metric + "' is not a metric. Use one "
                    + "of MEAN_LUMINANCE, BRIGHT_FRACTION, SATURATED_BRIGHT_FRACTION");
        }
    }

    private static String text(JsonNode node, String field, String where) {
        if (!node.hasNonNull(field) || node.get(field).asText().isBlank()) {
            throw new IllegalArgumentException(where + " needs a '" + field + "'");
        }
        return node.get(field).asText().trim();
    }

    private static double number(JsonNode node, String field, String where) {
        if (!node.hasNonNull(field)) {
            throw new IllegalArgumentException(where + " needs a '" + field + "'");
        }
        JsonNode value = node.get(field);
        if (!value.isNumber()) {
            throw new IllegalArgumentException(
                    where + ": '" + field + "' must be a number, got " + value);
        }
        return value.asDouble();
    }
}
