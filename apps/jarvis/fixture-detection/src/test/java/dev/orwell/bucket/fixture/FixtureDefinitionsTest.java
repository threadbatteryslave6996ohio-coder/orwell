package dev.orwell.bucket.fixture;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reading the fixture list, and refusing a bad one.
 *
 * <p>Every message asserted here is asserted because it is the only thing standing between an
 * operator and a detector that runs happily while watching nothing: silence is the normal output of a
 * working lamp detector, so a misconfiguration has no other symptom.
 */
class FixtureDefinitionsTest {

    @Test
    void readsTheFixturesFromTheDocumentedShape() {
        List<Fixture> fixtures = FixtureDefinitions.parse("""
                [
                  {
                    "name": "living-room-lamp",
                    "source": "cam1",
                    "region":    {"x": 0.62, "y": 0.18, "width": 0.10, "height": 0.12},
                    "reference": {"x": 0.20, "y": 0.18, "width": 0.10, "height": 0.12},
                    "metric": "MEAN_LUMINANCE",
                    "onAbove": 22, "offBelow": 10, "confirmFrames": 3
                  },
                  {
                    "name": "stove-burner",
                    "region": {"x": 0.40, "y": 0.70, "width": 0.14, "height": 0.10},
                    "metric": "SATURATED_BRIGHT_FRACTION",
                    "brightCutoff": 110, "saturationCutoff": 0.35,
                    "onAbove": 0.02, "offBelow": 0.005, "confirmFrames": 2
                  }
                ]
                """);

        assertThat(fixtures).hasSize(2);
        Fixture lamp = fixtures.get(0);
        assertThat(lamp.name()).isEqualTo("living-room-lamp");
        assertThat(lamp.source()).isEqualTo("cam1");
        assertThat(lamp.region().x()).isEqualTo(0.62);
        assertThat(lamp.reference()).isNotNull();
        assertThat(lamp.metric()).isEqualTo(FixtureMetric.MEAN_LUMINANCE);

        Fixture burner = fixtures.get(1);
        // No source: watches whichever camera the frame came from, the single-camera case.
        assertThat(burner.source()).isNull();
        assertThat(burner.reference()).isNull();
        assertThat(burner.metric()).isEqualTo(FixtureMetric.SATURATED_BRIGHT_FRACTION);
        assertThat(burner.brightCutoff()).isEqualTo(110);
        assertThat(burner.saturationCutoff()).isEqualTo(0.35);
        assertThat(burner.confirmFrames()).isEqualTo(2);
    }

    @Test
    void fillsInTheDefaultsForWhatAFixtureDoesNotSay() {
        Fixture fixture = FixtureDefinitions.parse("""
                [{"name": "lamp", "region": {"x": 0, "y": 0, "width": 1, "height": 1},
                  "onAbove": 50, "offBelow": 20}]
                """).get(0);

        assertThat(fixture.metric()).isEqualTo(FixtureMetric.MEAN_LUMINANCE);
        assertThat(fixture.brightCutoff()).isEqualTo(FixtureDefinitions.DEFAULT_BRIGHT_CUTOFF);
        assertThat(fixture.saturationCutoff())
                .isEqualTo(FixtureDefinitions.DEFAULT_SATURATION_CUTOFF);
        assertThat(fixture.confirmFrames()).isEqualTo(FixtureDefinitions.DEFAULT_CONFIRM_FRAMES);
    }

    /** Nothing configured is a fresh deployment, not an error — it warns at startup instead. */
    @Test
    void treatsAnEmptyConfigurationAsNoFixtures() {
        assertThat(FixtureDefinitions.parse(null)).isEmpty();
        assertThat(FixtureDefinitions.parse("")).isEmpty();
        assertThat(FixtureDefinitions.parse("   ")).isEmpty();
        assertThat(FixtureDefinitions.parse("[]")).isEmpty();
    }

    @Test
    void namesTheFixtureAndFieldItCouldNotRead() {
        assertThatThrownBy(() -> FixtureDefinitions.parse("""
                [{"name": "lamp", "region": {"x": 0, "y": 0, "width": 1, "height": 1}}]
                """))
                .hasMessageContaining("lamp")
                .hasMessageContaining("onAbove");

        assertThatThrownBy(() -> FixtureDefinitions.parse("""
                [{"name": "lamp", "onAbove": 5, "offBelow": 1}]
                """))
                .hasMessageContaining("lamp")
                .hasMessageContaining("region");

        assertThatThrownBy(() -> FixtureDefinitions.parse("""
                [{"region": {"x": 0, "y": 0, "width": 1, "height": 1}, "onAbove": 5, "offBelow": 1}]
                """))
                .hasMessageContaining("name");
    }

    @Test
    void refusesARegionThatIsNotInTheFrame() {
        assertThatThrownBy(() -> FixtureDefinitions.parse("""
                [{"name": "lamp", "region": {"x": 0.9, "y": 0, "width": 0.5, "height": 1},
                  "onAbove": 5, "offBelow": 1}]
                """))
                .hasMessageContaining("lamp")
                .hasMessageContaining("region")
                .hasMessageContaining("x + width");
    }

    @Test
    void refusesAMetricItDoesNotHave() {
        assertThatThrownBy(() -> FixtureDefinitions.parse("""
                [{"name": "lamp", "region": {"x": 0, "y": 0, "width": 1, "height": 1},
                  "metric": "VIBES", "onAbove": 5, "offBelow": 1}]
                """))
                .hasMessageContaining("VIBES")
                .hasMessageContaining("MEAN_LUMINANCE");
    }

    /** Names are the key in /fixtures, the preview URL, the log line and the alert. */
    @Test
    void refusesTwoFixturesWithTheSameName() {
        assertThatThrownBy(() -> FixtureDefinitions.parse("""
                [{"name": "lamp", "region": {"x": 0, "y": 0, "width": 0.5, "height": 1},
                  "onAbove": 5, "offBelow": 1},
                 {"name": "lamp", "region": {"x": 0.5, "y": 0, "width": 0.5, "height": 1},
                  "onAbove": 5, "offBelow": 1}]
                """))
                .hasMessageContaining("both called 'lamp'");
    }

    /**
     * Unknown fields are ignored, which is what lets fixtures.example.json carry a "_comment" on each
     * entry — JSON has no comments and the example needs to explain itself where it is read.
     */
    @Test
    void ignoresFieldsItDoesNotKnow() {
        List<Fixture> fixtures = FixtureDefinitions.parse("""
                [{"_comment": "the lamp by the sofa", "name": "lamp", "somethingElse": 42,
                  "region": {"x": 0, "y": 0, "width": 1, "height": 1},
                  "onAbove": 50, "offBelow": 20}]
                """);

        assertThat(fixtures).singleElement().extracting(Fixture::name).isEqualTo("lamp");
    }

    @Test
    void refusesSomethingThatIsNotAListOfFixtures() {
        assertThatThrownBy(() -> FixtureDefinitions.parse("not json at all"))
                .hasMessageContaining("not valid JSON");
        assertThatThrownBy(() -> FixtureDefinitions.parse("""
                {"name": "lamp"}
                """))
                .hasMessageContaining("must be a JSON array");
    }

    @Test
    void saysWhichFileItCouldNotRead() {
        assertThatThrownBy(() -> FixtureDefinitions.load("", "/no/such/fixtures.json"))
                .hasMessageContaining("FIXTURE_DETECTION_DEFINITIONS_FILE")
                .hasMessageContaining("/no/such/fixtures.json");
    }

    @Test
    void aFileWinsOverTheInlineValue() throws Exception {
        java.nio.file.Path file = java.nio.file.Files.createTempFile("fixtures", ".json");
        java.nio.file.Files.writeString(file, """
                [{"name": "from-the-file", "region": {"x": 0, "y": 0, "width": 1, "height": 1},
                  "onAbove": 5, "offBelow": 1}]
                """);
        try {
            List<Fixture> fixtures = FixtureDefinitions.load("""
                    [{"name": "inline", "region": {"x": 0, "y": 0, "width": 1, "height": 1},
                      "onAbove": 5, "offBelow": 1}]
                    """, file.toString());

            assertThat(fixtures).singleElement()
                    .extracting(Fixture::name).isEqualTo("from-the-file");
        } finally {
            java.nio.file.Files.deleteIfExists(file);
        }
    }
}
