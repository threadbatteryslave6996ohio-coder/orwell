package dev.clippy.combined;

import org.hibernate.cfg.AvailableSettings;
import org.springframework.core.env.Environment;

import java.util.Map;

final class CombinedJpaProperties {
    private CombinedJpaProperties() {
    }

    static Map<String, Object> from(Environment environment, String ddlAutoProperty) {
        return Map.of(
                AvailableSettings.HBM2DDL_AUTO, environment.getRequiredProperty(ddlAutoProperty),
                "hibernate.jdbc.time_zone", environment.getRequiredProperty("clippy.jpa.jdbc-time-zone")
        );
    }
}
