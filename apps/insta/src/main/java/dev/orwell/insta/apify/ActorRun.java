package dev.orwell.insta.apify;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * One finished actor run: the dataset items it produced, and the {@code OUTPUT} record from its
 * key-value store.
 *
 * <p>They are separate storages on Apify's side, which is the whole reason this type exists. The
 * accounts land in the dataset; the pagination cursor lands in {@code OUTPUT}. An endpoint that
 * returns only the dataset — like the one the profile lookup uses — cannot see the cursor at all.
 *
 * @param output the {@code OUTPUT} record, or {@code null} when the run wrote none.
 */
public record ActorRun(List<JsonNode> items, JsonNode output) {
}
