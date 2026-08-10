package dev.orwell.insta.instagram;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Reads a value out of an Apify dataset item by trying several spellings of the same field.
 *
 * <p>The actors that scrape Instagram are third-party and interchangeable — that is the point of
 * {@code APIFY_PROFILE_ACTOR} / {@code APIFY_CONNECTIONS_ACTOR} — and they disagree on casing
 * ({@code full_name} vs {@code fullName}) far more often than on meaning. Accepting the aliases
 * here is what lets a different actor be swapped in without a code change.
 */
final class DatasetFields {

    private DatasetFields() {
    }

    /** The first of {@code names} present and non-null, or {@code null} if none of them are. */
    static String text(JsonNode item, String... names) {
        JsonNode value = first(item, names);
        return value == null || !value.isValueNode() ? null : value.asText();
    }

    /** The first of {@code names} that holds a number, or {@code null} — absent is not zero. */
    static Long number(JsonNode item, String... names) {
        JsonNode value = first(item, names);
        if (value == null || !value.isNumber()) {
            return null;
        }
        return value.asLong();
    }

    /** The first of {@code names} that holds a boolean, or {@code null} — absent is not false. */
    static Boolean flag(JsonNode item, String... names) {
        JsonNode value = first(item, names);
        if (value == null || !value.isBoolean()) {
            return null;
        }
        return value.asBoolean();
    }

    private static JsonNode first(JsonNode item, String... names) {
        if (item == null || !item.isObject()) {
            return null;
        }
        for (String name : names) {
            JsonNode value = item.get(name);
            if (value != null && !value.isNull()) {
                return value;
            }
        }
        return null;
    }
}
