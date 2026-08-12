package dev.orwell.reverseproxy;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ProxyRequestTest {
    @Test
    void headerLookupIgnoresCase() {
        ProxyRequest request = new ProxyRequest(
                "get", "/x", null, Map.of("authorization", List.of("Bearer token")), "10.0.0.1");

        assertEquals("Bearer token", request.header("Authorization"));
        assertNull(request.header("X-Missing"));
    }

    @Test
    void methodIsNormalizedAndAbsentPartsBecomeEmpty() {
        ProxyRequest request = new ProxyRequest("get", "/x", null, Map.of(), null);

        assertEquals("GET", request.method());
        assertEquals("", request.query());
        assertEquals("", request.remoteAddress());
        assertEquals("/x", request.target());
    }

    @Test
    void targetKeepsTheQueryStringAndItsEncoding() {
        ProxyRequest request = new ProxyRequest("GET", "/a%20b", "q=1&r=2", Map.of(), "10.0.0.1");

        assertEquals("/a%20b?q=1&r=2", request.target());
    }
}
