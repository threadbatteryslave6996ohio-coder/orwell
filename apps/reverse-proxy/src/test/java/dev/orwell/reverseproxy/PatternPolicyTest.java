package dev.orwell.reverseproxy;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PatternPolicyTest {
    private static ProxyRequest request(String method, String path, String query) {
        return new ProxyRequest(method, path, query, Map.of(), "127.0.0.1");
    }

    @Test
    void blocksAMatchingPathForEveryMethod() {
        PatternPolicy policy = PatternPolicy.fromConfiguration("/admin.*");

        assertFalse(policy.pass(request("GET", "/admin", null)));
        assertFalse(policy.pass(request("POST", "/admin/users", null)));
        assertTrue(policy.pass(request("GET", "/public", null)));
    }

    @Test
    void matchesTheWholeTargetRatherThanASubstring() {
        PatternPolicy policy = PatternPolicy.fromConfiguration("/admin");

        assertFalse(policy.pass(request("GET", "/admin", null)));
        // Without the .* the subtree stays open — this is the trap the README calls out.
        assertTrue(policy.pass(request("GET", "/admin/users", null)));
    }

    @Test
    void aMethodPrefixScopesThePatternToThatMethod() {
        PatternPolicy policy = PatternPolicy.fromConfiguration("post:/orders");

        assertFalse(policy.pass(request("POST", "/orders", null)));
        assertTrue(policy.pass(request("GET", "/orders", null)));
    }

    @Test
    void theQueryStringIsPartOfTheMatchedTarget() {
        PatternPolicy policy = PatternPolicy.fromConfiguration(".*[?&]debug=true");

        assertFalse(policy.pass(request("GET", "/report", "debug=true")));
        assertTrue(policy.pass(request("GET", "/report", "debug=false")));
    }

    @Test
    void aColonInsideAPatternIsNotReadAsAMethodScope() {
        PatternPolicy policy = PatternPolicy.fromConfiguration("/a:b");

        assertFalse(policy.pass(request("GET", "/a:b", null)));
    }

    @Test
    void blankAndEmptyConfigurationBlocksNothing() {
        assertTrue(PatternPolicy.fromConfiguration("").pass(request("GET", "/admin", null)));
        assertTrue(PatternPolicy.fromConfiguration(null).pass(request("GET", "/admin", null)));
        assertEquals(List.of(), PatternPolicy.fromConfiguration("  ,  ").patterns());
    }

    @Test
    void anUncompilablePatternIsRejectedRatherThanIgnored() {
        // Silently dropping it would leave the operator with a blocklist that blocks nothing.
        assertThrows(IllegalArgumentException.class, () -> PatternPolicy.fromConfiguration("/admin["));
    }

    @Test
    void patternsAreReportedForHealthOutput() {
        PatternPolicy policy = PatternPolicy.fromConfiguration("/admin.*, POST:/internal/.*");

        assertEquals(List.of("/admin.*", "POST:/internal/.*"), policy.patterns());
    }
}
