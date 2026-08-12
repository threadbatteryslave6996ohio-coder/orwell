package dev.orwell.reverseproxy;

import java.util.List;
import java.util.Objects;

/**
 * Runs every {@link Policy} in order and stops at the first refusal, so an expensive policy can
 * be ordered behind a cheap one. An empty chain allows everything — a proxy with no policies
 * configured is a plain pass-through, not a closed door.
 */
public final class PolicyChain {
    private final List<Policy> policies;

    public PolicyChain(List<Policy> policies) {
        this.policies = List.copyOf(Objects.requireNonNull(policies, "policies"));
    }

    public PolicyDecision evaluate(ProxyRequest request) {
        Objects.requireNonNull(request, "request");
        for (Policy policy : policies) {
            if (!policy.pass(request)) {
                return PolicyDecision.deny(policy.name());
            }
        }
        return PolicyDecision.allow();
    }

    /** Policy names in evaluation order, for {@code /health} and startup logging. */
    public List<String> names() {
        return policies.stream().map(Policy::name).toList();
    }
}
