package dev.clippy.utils.envmanager;

import java.util.Map;

/** Shared indexing/validation helpers for {@link EnvOption} collections. */
final class EnvOptions {
    private EnvOptions() {
    }

    /**
     * Inserts {@code option} into {@code target} keyed by its name, throwing
     * {@link IllegalArgumentException} if an option with the same name is already present.
     */
    static void putUnique(Map<String, EnvOption<?>> target, EnvOption<?> option) {
        EnvOption<?> previous = target.putIfAbsent(option.name(), option);
        if (previous != null) {
            throw new IllegalArgumentException("Duplicate environment option: " + option.name());
        }
    }
}
