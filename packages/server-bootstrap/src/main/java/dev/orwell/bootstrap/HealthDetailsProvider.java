package dev.orwell.bootstrap;

import java.util.Map;

@FunctionalInterface
public interface HealthDetailsProvider {
    Map<String, Object> details();
}
