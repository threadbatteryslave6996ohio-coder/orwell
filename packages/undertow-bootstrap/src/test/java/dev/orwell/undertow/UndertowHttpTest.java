package dev.orwell.undertow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UndertowHttpTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void readsJsonObjects() throws Exception {
        assertEquals(Map.of("message", "hello"), read("{\"message\":\"hello\"}"));
    }

    @Test
    void classifiesEmptyAndWhitespaceOnlyBodiesAsMissing() {
        assertBodyError("", "missing request body");
        assertBodyError("  \n\t", "missing request body");
    }

    @Test
    void classifiesMalformedJsonAsInvalidJson() {
        assertBodyError("{", "invalid json");
    }

    @Test
    void classifiesValidNonObjectJsonAsInvalidRequestBody() {
        assertBodyError("[]", "invalid request body");
        assertBodyError("\"text\"", "invalid request body");
        assertBodyError("42", "invalid request body");
        assertBodyError("null", "invalid request body");
    }

    @Test
    void classifiesOversizedBodiesAsPayloadTooLarge() {
        InputStream oversized = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new io.undertow.server.RequestTooBigException();
            }
        };

        UndertowHttp.RequestBodyException exception = assertThrows(
                UndertowHttp.RequestBodyException.class,
                () -> UndertowHttp.readObject(oversized));
        assertEquals(413, exception.statusCode());
        assertEquals("request body too large", exception.getMessage());
    }

    @Test
    void oversizedHttpRequestsReceiveJson413() throws Exception {
        var routes = UndertowHttp.routes()
                .post("/body", UndertowHttp.jsonObject(32, body ->
                        dev.orwell.http.EndpointResponse.ok(body)));
        var server = UndertowHttp.start("127.0.0.1", 0, routes);
        try {
            InetSocketAddress address = (InetSocketAddress) server.getListenerInfo().getFirst().getAddress();
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + address.getPort() + "/body"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"value\":\"" + "x".repeat(64) + "\"}"))
                    .build();

            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(413, response.statusCode());
            assertEquals(Map.of("success", false, "error", "request body too large"),
                    JSON.readValue(response.body(), Map.class));
        } finally {
            server.stop();
        }
    }

    private static Map<String, Object> read(String body) throws Exception {
        return UndertowHttp.readObject(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }

    private static void assertBodyError(String body, String expected) {
        UndertowHttp.RequestBodyException exception = assertThrows(
                UndertowHttp.RequestBodyException.class,
                () -> read(body));
        assertEquals(expected, exception.getMessage());
        assertEquals(400, exception.statusCode());
    }
}
