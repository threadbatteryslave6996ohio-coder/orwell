package dev.clippy.combined;

import org.hibernate.cfg.AvailableSettings;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class CombinedJpaPropertiesTest {
    @Test
    void mapsModuleSpecificDdlModeAndSharedTimeZone() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("clippy.auth.jpa.hibernate.ddl-auto", "validate")
                .withProperty("clippy.jpa.jdbc-time-zone", "UTC");

        assertThat(CombinedJpaProperties.from(environment, "clippy.auth.jpa.hibernate.ddl-auto"))
                .containsExactlyInAnyOrderEntriesOf(java.util.Map.of(
                        AvailableSettings.HBM2DDL_AUTO, "validate",
                        "hibernate.jdbc.time_zone", "UTC"
                ));
    }
}
