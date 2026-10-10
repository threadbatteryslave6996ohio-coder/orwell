package dev.orwell.http;

import dev.orwell.testing.http.JsonStubServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercised against a real server on a loopback port: what matters is the header that arrives on
 * the wire, which a mocked client could not tell us.
 */
class ServiceHeaderHttpClientTest {
    private final List<String> receivedService = Collections.synchronizedList(new ArrayList<>());
    private final List<String> receivedBodies = Collections.synchronizedList(new ArrayList<>());
    private final List<String> receivedMethods = Collections.synchronizedList(new ArrayList<>());

    private JsonStubServer server;
    private URI url;

    @BeforeEach
    void startServer() throws IOException {
        server = JsonStubServer.start();
        server.on("/", exchange -> {
            receivedService.add(String.valueOf(exchange.getRequestHeaders().getFirst("Service")));
            receivedMethods.add(exchange.getRequestMethod());
            receivedBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        url = URI.create(server.baseUrl() + "/thing");
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    private HttpResponse<Void> send(HttpClient client, HttpRequest request) throws IOException, InterruptedException {
        return client.send(request, HttpResponse.BodyHandlers.discarding());
    }

    @Test
    void everyRequestCarriesTheServiceName() throws Exception {
        HttpClient client = ServiceHeaderHttpClient.wrap(HttpClient.newHttpClient(), "log-analyzer");

        HttpResponse<Void> response = send(client, HttpRequest.newBuilder(url).build());

        assertEquals(204, response.statusCode());
        assertEquals(List.of("log-analyzer"), receivedService);
    }

    @Test
    void theMethodBodyAndOtherHeadersSurviveTheCopy() throws Exception {
        HttpClient client = ServiceHeaderHttpClient.wrap(HttpClient.newHttpClient(), "insta");

        send(client, HttpRequest.newBuilder(url)
                .header("X-Client", "test")
                .timeout(Duration.ofSeconds(5))
                .PUT(HttpRequest.BodyPublishers.ofString("payload"))
                .build());

        assertEquals(List.of("PUT"), receivedMethods);
        assertEquals(List.of("payload"), receivedBodies);
        assertEquals(List.of("insta"), receivedService);
    }

    @Test
    void aServiceHeaderSetAtTheCallSiteIsNotOverwritten() throws Exception {
        HttpClient client = ServiceHeaderHttpClient.wrap(HttpClient.newHttpClient(), "wrapper-name");

        send(client, HttpRequest.newBuilder(url).header("Service", "call-site-name").build());

        assertEquals(List.of("call-site-name"), receivedService);
    }

    @Test
    void aRequestThatAlreadyCarriesTheHeaderIsForwardedUntouched() {
        // The identity check is the point: an already-attributed request costs nothing to relay.
        RecordingClient recorder = new RecordingClient();
        HttpClient client = ServiceHeaderHttpClient.wrap(recorder, "wrapper-name");
        HttpRequest request = HttpRequest.newBuilder(url).header("Service", "upstream-caller").build();

        client.sendAsync(request, HttpResponse.BodyHandlers.discarding());

        assertSame(request, recorder.sent.getFirst());
    }

    @Test
    void wrappingTwiceAppliesTheOutermostName() throws Exception {
        HttpClient client = ServiceHeaderHttpClient.wrap(
                ServiceHeaderHttpClient.wrap(HttpClient.newHttpClient(), "inner"), "outer");

        send(client, HttpRequest.newBuilder(url).build());

        assertEquals(List.of("outer"), receivedService);
    }

    @Test
    void theHeaderIsMatchedWithoutRegardToCase() throws Exception {
        HttpClient client = ServiceHeaderHttpClient.wrap(HttpClient.newHttpClient(), "wrapper-name");

        send(client, HttpRequest.newBuilder(url).header("service", "lowercase-call-site").build());

        assertEquals(List.of("lowercase-call-site"), receivedService);
    }

    @Test
    void theAsyncPathsStampToo() throws Exception {
        HttpClient client = ServiceHeaderHttpClient.wrap(HttpClient.newHttpClient(), "jarvis-person-detection");

        client.sendAsync(HttpRequest.newBuilder(url).build(), HttpResponse.BodyHandlers.discarding()).get();
        client.sendAsync(HttpRequest.newBuilder(url).build(), HttpResponse.BodyHandlers.discarding(), null).get();

        assertEquals(List.of("jarvis-person-detection", "jarvis-person-detection"), receivedService);
    }

    @Test
    void aBlankOrUnsendableNameIsRejectedAtConstruction() {
        HttpClient plain = HttpClient.newHttpClient();

        assertThrows(IllegalArgumentException.class, () -> ServiceHeaderHttpClient.wrap(plain, "  "));
        assertThrows(NullPointerException.class, () -> ServiceHeaderHttpClient.wrap(plain, null));
        // A newline in a header value is header injection, not a name.
        assertThrows(IllegalArgumentException.class,
                () -> ServiceHeaderHttpClient.wrap(plain, "log-analyzer\r\nX-Admin: true"));
    }

    @Test
    void theWrapperReportsTheDelegatesConfiguration() {
        HttpClient delegate = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(7))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        HttpClient client = ServiceHeaderHttpClient.wrap(delegate, "backup");

        assertEquals(delegate.connectTimeout(), client.connectTimeout());
        assertEquals(HttpClient.Redirect.NEVER, client.followRedirects());
        assertEquals(delegate.version(), client.version());
        assertEquals(delegate.executor().isPresent(), client.executor().isPresent());
        assertNotNull(client.sslContext());
        assertNotNull(client.sslParameters());
        assertTrue(client.proxy().isEmpty());
        assertTrue(client.authenticator().isEmpty());
        assertTrue(client.cookieHandler().isEmpty());
        assertEquals("backup", ((ServiceHeaderHttpClient) client).service());
    }

    @Test
    void closingTheWrapperClosesTheClientItWraps() throws Exception {
        HttpClient delegate = HttpClient.newHttpClient();
        HttpClient client = ServiceHeaderHttpClient.wrap(delegate, "backup");

        client.close();

        assertTrue(delegate.isTerminated());
        assertTrue(client.isTerminated());
        assertTrue(client.awaitTermination(Duration.ofSeconds(1)));
        // A closed client refuses further work, which is what proves the close reached the delegate.
        assertThrows(IOException.class, () -> send(client, HttpRequest.newBuilder(url).build()));
    }

    /**
     * Captures what the wrapper hands down, for the assertions a real server cannot make. Extends
     * {@link DelegatingHttpClient} for the same reason the wrappers do: only the send methods are
     * interesting here, and the configuration surface is a real client's.
     */
    private static final class RecordingClient extends DelegatingHttpClient {
        private final List<HttpRequest> sent = Collections.synchronizedList(new ArrayList<>());

        RecordingClient() {
            super(HttpClient.newHttpClient());
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            sent.add(request);
            throw new UnsupportedOperationException("not sent");
        }

        @Override
        public <T> java.util.concurrent.CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            sent.add(request);
            return new java.util.concurrent.CompletableFuture<>();
        }

        @Override
        public <T> java.util.concurrent.CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> handler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            sent.add(request);
            return new java.util.concurrent.CompletableFuture<>();
        }
    }
}
