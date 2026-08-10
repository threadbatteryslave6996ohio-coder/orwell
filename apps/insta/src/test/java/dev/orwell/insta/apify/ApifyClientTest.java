package dev.orwell.insta.apify;

import com.fasterxml.jackson.databind.JsonNode;
import dev.orwell.insta.support.ApifyStubServer;
import dev.orwell.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The wire contract with Apify: how a run is addressed, authenticated, bounded, and how it fails. */
class ApifyClientTest {
    private static final Logger NO_OP_LOGGER = entry -> {
    };

    private ApifyStubServer apify;

    @BeforeEach
    void startStub() throws IOException {
        apify = ApifyStubServer.start();
    }

    @AfterEach
    void stopStub() {
        apify.close();
    }

    @Test
    void addressesTheActorWithATildeAndBoundsTheRun() {
        apify.responds(200, "[]");

        client(120).runActor("apify/instagram-profile-scraper", Map.of("usernames", List.of("nasa")), 25);

        ApifyStubServer.Request request = apify.lastRequest();
        assertThat(request.path()).isEqualTo("/v2/acts/apify~instagram-profile-scraper/run-sync-get-dataset-items");
        assertThat(request.query()).contains("timeout=120").contains("maxItems=25");
        assertThat(request.body()).isEqualTo("{\"usernames\":[\"nasa\"]}");
    }

    /** The token belongs in a header: a query parameter would land in every proxy log on the way. */
    @Test
    void sendsTheTokenAsABearerHeaderAndNeverInTheUrl() {
        apify.responds(200, "[]");

        client(120).runActor("apify/x", Map.of(), 1);

        assertThat(apify.lastRequest().authorization()).isEqualTo("Bearer test-token");
        assertThat(apify.lastRequest().query()).doesNotContain("test-token");
    }

    /** Apify caps sync runs at 300s, so asking for longer would have the run rejected outright. */
    @Test
    void clampsTheSyncRunTimeoutToWhatApifyAccepts() {
        apify.responds(200, "[]");

        client(9_000).runActor("apify/x", Map.of(), 1);

        assertThat(apify.lastRequest().query())
                .contains("timeout=" + ApifyClient.MAX_RUN_TIMEOUT_SECONDS);
    }

    @Test
    void returnsTheDatasetItems() {
        apify.responds(200, "[{\"username\":\"a\"},{\"username\":\"b\"}]");

        List<JsonNode> items = client(120).runActor("apify/x", Map.of(), 10);

        assertThat(items).hasSize(2);
        assertThat(items.get(1).get("username").asText()).isEqualTo("b");
    }

    /** An actor that ignores the cap must not be able to hand a caller more than it asked for. */
    @Test
    void truncatesItemsToTheRequestedMaximum() {
        apify.responds(200, "[{\"username\":\"a\"},{\"username\":\"b\"},{\"username\":\"c\"}]");

        assertThat(client(120).runActor("apify/x", Map.of(), 2)).hasSize(2);
    }

    /** 408 is the one failure a caller can do something about, so it stays distinguishable. */
    @Test
    void reportsARunThatOutlivedItsTimeoutAsTimedOut() {
        apify.responds(408, "{\"error\":{\"type\":\"request-timeout\"}}");

        assertThatThrownBy(() -> client(30).runActor("apify/x", Map.of(), 1))
                .isInstanceOfSatisfying(ApifyException.class,
                        exception -> assertThat(exception.kind())
                                .isEqualTo(ApifyException.Kind.TIMED_OUT))
                .hasMessageContaining("30s");
    }

    @Test
    void reportsARejectedTokenWithoutClaimingATimeout() {
        apify.responds(401, "{\"error\":{\"type\":\"token-not-provided\"}}");

        assertThatThrownBy(() -> client(120).runActor("apify/x", Map.of(), 1))
                .isInstanceOfSatisfying(ApifyException.class,
                        exception -> assertThat(exception.kind())
                                .isEqualTo(ApifyException.Kind.TOKEN_REJECTED));
    }

    /**
     * Apify does not document a status for an exhausted balance, so the error type has to carry it.
     * A 403 alone would otherwise read as a bad token and send someone rotating a working one.
     */
    @Test
    void tellsAnExhaustedBalanceApartFromARejectedTokenByErrorType() {
        apify.responds(403, "{\"error\":{\"type\":\"monthly-usage-hard-limit-exceeded\"}}");

        assertThatThrownBy(() -> client(120).runActor("apify/x", Map.of(), 1))
                .isInstanceOfSatisfying(ApifyException.class,
                        exception -> assertThat(exception.kind())
                                .isEqualTo(ApifyException.Kind.OUT_OF_CREDIT));
    }

    @Test
    void reportsThrottlingAsRateLimited() {
        apify.responds(429, "{\"error\":{\"type\":\"rate-limit-exceeded\"}}");

        assertThatThrownBy(() -> client(120).runActor("apify/x", Map.of(), 1))
                .isInstanceOfSatisfying(ApifyException.class,
                        exception -> assertThat(exception.kind())
                                .isEqualTo(ApifyException.Kind.RATE_LIMITED));
    }

    @Test
    void refusesABodyThatIsNotADatasetArray() {
        apify.responds(200, "{\"error\":\"actor not found\"}");

        assertThatThrownBy(() -> client(120).runActor("apify/x", Map.of(), 1))
                .isInstanceOf(ApifyException.class)
                .hasMessageContaining("unexpected shape");
    }

    @Test
    void reportsAnUnreachableApifyRatherThanFailingSilently() {
        // Port 1 is reserved and refuses connections, standing in for Apify being unreachable.
        ApifyClient client = new ApifyClient("http://127.0.0.1:1", "test-token", 5, NO_OP_LOGGER);

        assertThatThrownBy(() -> client.runActor("apify/x", Map.of(), 1))
                .isInstanceOf(ApifyException.class)
                .hasMessageContaining("Could not reach Apify");
    }

    /** A base URL pasted with a trailing slash must not produce a double-slashed path. */
    @Test
    void toleratesATrailingSlashOnTheBaseUrl() {
        apify.responds(200, "[]");

        new ApifyClient(apify.baseUrl() + "/", "test-token", 120, NO_OP_LOGGER)
                .runActor("apify/x", Map.of(), 1);

        assertThat(apify.lastRequest().path()).startsWith("/v2/acts/");
    }

    // ------------------------------------------------- the asynchronous path

    /**
     * The dataset and the OUTPUT record are separate storages on Apify's side, and only this path
     * reads both — which is the whole reason it exists.
     */
    @Test
    void readsBothTheDatasetAndTheOutputRecordOfAnAsynchronousRun() {
        apify.responds(200, "[{\"username\":\"alice\"}]");
        apify.outputs("{\"continuations\":[{\"nextContinuationToken\":\"TOKEN-2\"}]}");

        ActorRun run = client(120).runActorWithOutput("vendor/x", Map.of("Account", "nasa"), 50);

        assertThat(run.items()).hasSize(1);
        // Asserted on the raw record, not through the Instagram cursor reader: what this test
        // owns is that OUTPUT arrived at all, and reading it is the other package's job.
        assertThat(run.output().path("continuations").get(0).path("nextContinuationToken").asText())
                .isEqualTo("TOKEN-2");
        assertThat(apify.runStart().path()).isEqualTo("/v2/acts/vendor~x/runs");
        assertThat(apify.runStart().query()).contains("maxItems=50");
    }

    /** Most actors never write an OUTPUT record; that is an absent cursor, not a failed run. */
    @Test
    void treatsAMissingOutputRecordAsNoCursorRatherThanAFailure() {
        apify.responds(200, "[{\"username\":\"alice\"}]");

        ActorRun run = client(120).runActorWithOutput("vendor/x", Map.of(), 50);

        assertThat(run.items()).hasSize(1);
        assertThat(run.output()).isNull();
    }

    @Test
    void failsWhenTheRunEndsWithoutSucceeding() {
        apify.responds(200, "[]");
        apify.runSettlesAs("FAILED");

        assertThatThrownBy(() -> client(120).runActorWithOutput("vendor/x", Map.of(), 50))
                .isInstanceOfSatisfying(ApifyException.class,
                        exception -> assertThat(exception.kind())
                                .isEqualTo(ApifyException.Kind.UNAVAILABLE));
    }

    /** An orphaned run keeps billing, so giving up on one has to also stop it. */
    @Test
    void abortsARunItGivesUpWaitingFor() {
        apify.responds(200, "[]");
        apify.runSettlesAs("RUNNING");

        assertThatThrownBy(() -> client(1).runActorWithOutput("vendor/x", Map.of(), 50))
                .isInstanceOfSatisfying(ApifyException.class,
                        exception -> assertThat(exception.kind())
                                .isEqualTo(ApifyException.Kind.TIMED_OUT));

        assertThat(apify.sawRequestTo("/abort")).isTrue();
    }

    private ApifyClient client(int runTimeoutSeconds) {
        return new ApifyClient(apify.baseUrl(), "test-token", runTimeoutSeconds, NO_OP_LOGGER);
    }
}
