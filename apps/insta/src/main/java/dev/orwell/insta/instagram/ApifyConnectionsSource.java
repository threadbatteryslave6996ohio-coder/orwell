package dev.orwell.insta.instagram;

import dev.orwell.insta.apify.ActorRun;
import dev.orwell.insta.apify.ApifyClient;
import dev.orwell.insta.apify.ApifyException;
import dev.orwell.logging.Logger;

import java.util.Map;
import java.util.Objects;

/**
 * The three marketplace actors, as a {@link ConnectionsSource}.
 *
 * <p>Pure adaptation and nothing else: it holds a {@link ConnectionsAdapter}, runs the actor that
 * adapter names, and hands back the dataset. The adapters themselves were not touched when sources
 * were introduced — they already described their actor correctly, and the thing that changed is
 * that <em>describing an actor</em> is now one way of being a source rather than the only way.
 */
public final class ApifyConnectionsSource implements ConnectionsSource {
    private final ConnectionsAdapter adapter;
    private final ApifyClient apify;
    private final Logger logger;

    public ApifyConnectionsSource(ConnectionsAdapter adapter, ApifyClient apify, Logger logger) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.apify = Objects.requireNonNull(apify, "apify");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public String name() {
        return adapter.name();
    }

    @Override
    public boolean supports(ConnectionType type) {
        return adapter.supports(type);
    }

    @Override
    public boolean supportsCursor() {
        return adapter.supportsCursor();
    }

    @Override
    public Result fetch(String username, ConnectionType type, int limit, String cursor) {
        ActorRun run;
        try {
            run = apify.runActorWithOutput(
                    adapter.actorId(), adapter.input(username, type, limit, cursor), limit);
        } catch (ApifyException exception) {
            logger.error("Instagram lookup failed upstream.", Map.of(
                    "username", username, "source", adapter.name(), "actor", adapter.actorId(),
                    "kind", exception.kind().name(), "error", exception.toString()));
            throw exception;
        }
        // An actor that reported a failure in OUTPUT rather than by failing the run: an empty
        // dataset from a successful-looking run is otherwise an account with no followers.
        adapter.verify(run.output());
        return new Result(
                run.items(), adapter.supportsCursor() ? adapter.nextToken(run.output()) : null);
    }

    /** The actor behind this source, for messages that want to name it. */
    public String actorId() {
        return adapter.actorId();
    }
}
