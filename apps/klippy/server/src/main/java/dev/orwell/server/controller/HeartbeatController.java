package dev.orwell.server.controller;

import dev.orwell.auth.AuthenticationContext;
import dev.orwell.bootstrap.auth.RequireAuthentication;
import dev.orwell.logging.Logger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Objects;

/**
 * Records client liveness heartbeats. The client posts here on its own cadence; the beat is logged
 * through the shared {@link Logger} (which ships to Loki), and an external monitor watches for the
 * <em>absence</em> of these lines to decide a client has stopped running. Nothing is persisted:
 * the log line is the whole signal, and the client id comes from the authenticated token, not the
 * request body.
 */
@RestController
public class HeartbeatController {
    private final ObjectProvider<AuthenticationContext> authenticationContextProvider;
    private final Logger logger;

    public HeartbeatController(
            ObjectProvider<AuthenticationContext> authenticationContextProvider,
            Logger logger
    ) {
        this.authenticationContextProvider = authenticationContextProvider;
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    // The shared interceptor enforces the 401 {"error":"authentication required"} contract before
    // this runs, so the caller is already authenticated; we only read the context for its clientId.
    @RequireAuthentication
    @PostMapping("/heartbeat")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void heartbeat() {
        AuthenticationContext authenticationContext = authenticationContextProvider.getObject();
        try {
            logger.info("Client heartbeat.", Map.of("clientId", authenticationContext.clientId()));
        } catch (RuntimeException exception) {
            // The heartbeat's whole purpose is the log line, but a sink failure must not turn a
            // healthy beat into a 500 that the client would read as the server being down.
        }
    }
}
