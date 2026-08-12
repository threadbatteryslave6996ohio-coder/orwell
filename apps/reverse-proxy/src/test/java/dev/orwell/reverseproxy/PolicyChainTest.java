package dev.orwell.reverseproxy;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicyChainTest {
    private static final ProxyRequest REQUEST =
            new ProxyRequest("GET", "/reports", null, Map.of(), "127.0.0.1");

    private static Policy named(String name, boolean passes) {
        return new Policy() {
            @Override
            public boolean pass(ProxyRequest request) {
                return passes;
            }

            @Override
            public String name() {
                return name;
            }
        };
    }

    @Test
    void anEmptyChainAllowsTheRequest() {
        assertTrue(new PolicyChain(List.of()).evaluate(REQUEST).allowed());
    }

    @Test
    void theFirstRefusalNamesTheDecision() {
        PolicyChain chain = new PolicyChain(List.of(
                named("allow-all", true),
                named("blocklist", false),
                named("never-reached", false)));

        PolicyDecision decision = chain.evaluate(REQUEST);

        assertFalse(decision.allowed());
        assertEquals("blocklist", decision.policy());
    }

    @Test
    void evaluationStopsAtTheFirstRefusal() {
        AtomicInteger later = new AtomicInteger();
        PolicyChain chain = new PolicyChain(List.of(
                named("blocklist", false),
                request -> {
                    later.incrementAndGet();
                    return true;
                }));

        chain.evaluate(REQUEST);

        assertEquals(0, later.get());
    }

    @Test
    void namesAreReportedInEvaluationOrder() {
        PolicyChain chain = new PolicyChain(List.of(named("first", true), named("second", true)));

        assertEquals(List.of("first", "second"), chain.names());
    }
}
