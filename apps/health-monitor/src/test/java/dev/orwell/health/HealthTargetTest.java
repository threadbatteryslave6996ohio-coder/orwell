package dev.orwell.health;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reading the target list, and refusing a bad one.
 *
 * <p>Fatal rather than skipped, because a monitor that quietly drops one malformed entry watches less
 * than it was told to and reports nothing about the difference — and no alerts is what a healthy stack
 * looks like, so there would be no symptom.
 */
class HealthTargetTest {

    @Test
    void readsACommaSeparatedNameEqualsUrlList() {
        List<HealthTarget> targets = HealthTarget.parse(
                "auth=http://auth-server/auth/health,hub=http://jarvis-hub:9001/health");

        assertThat(targets).hasSize(2);
        assertThat(targets.get(0).name()).isEqualTo("auth");
        assertThat(targets.get(0).url().toString()).isEqualTo("http://auth-server/auth/health");
        assertThat(targets.get(1).url().getPort()).isEqualTo(9001);
    }

    @Test
    void toleratesSpacingAndTrailingSeparators() {
        List<HealthTarget> targets = HealthTarget.parse(
                "  auth = http://auth-server/auth/health , hub=http://hub:9001/health , ");

        assertThat(targets).extracting(HealthTarget::name).containsExactly("auth", "hub");
        assertThat(targets.get(0).url().toString()).isEqualTo("http://auth-server/auth/health");
    }

    /** Only the first '=' splits, so a query string survives. */
    @Test
    void keepsAUrlThatContainsItsOwnEqualsSign() {
        List<HealthTarget> targets =
                HealthTarget.parse("grafana=http://grafana/api/health?verbose=true");

        assertThat(targets).singleElement()
                .extracting(target -> target.url().toString())
                .isEqualTo("http://grafana/api/health?verbose=true");
    }

    /** Nothing configured is a fresh deployment, not an error — it warns at startup instead. */
    @Test
    void treatsAnEmptyListAsNoTargets() {
        assertThat(HealthTarget.parse(null)).isEmpty();
        assertThat(HealthTarget.parse("")).isEmpty();
        assertThat(HealthTarget.parse("   ")).isEmpty();
    }

    @Test
    void refusesAnEntryThatIsNotNameEqualsUrl() {
        assertThatThrownBy(() -> HealthTarget.parse("http://auth-server/health"))
                .hasMessageContaining("name=url");
        assertThatThrownBy(() -> HealthTarget.parse("=http://auth-server/health"))
                .hasMessageContaining("name=url");
        assertThatThrownBy(() -> HealthTarget.parse("auth="))
                .hasMessageContaining("name=url");
    }

    /**
     * A relative URL would otherwise reach HttpClient as an exception per request, reading as every
     * check failing rather than as one typo.
     */
    @Test
    void refusesAUrlWithoutASchemeAndHost() {
        assertThatThrownBy(() -> HealthTarget.parse("auth=/auth/health"))
                .hasMessageContaining("auth")
                .hasMessageContaining("absolute url");
        assertThatThrownBy(() -> HealthTarget.parse("auth=auth-server/health"))
                .hasMessageContaining("absolute url");
    }

    /** Names key /targets and the alerts; two the same makes both ambiguous. */
    @Test
    void refusesTwoTargetsWithTheSameName() {
        assertThatThrownBy(() -> HealthTarget.parse(
                "auth=http://a/health,auth=http://b/health"))
                .hasMessageContaining("both called 'auth'");
    }
}
