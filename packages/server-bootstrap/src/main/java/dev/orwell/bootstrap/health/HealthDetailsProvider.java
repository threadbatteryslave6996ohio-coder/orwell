package dev.orwell.bootstrap.health;

import java.util.Map;

@FunctionalInterface
public interface HealthDetailsProvider {
    Map<String, Object> details();
}
