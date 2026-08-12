package dev.orwell.reverseproxy;

import java.util.Objects;

/** Outcome of running the policy chain: allowed, or denied by the named policy. */
public record PolicyDecision(boolean allowed, String policy) {
    private static final PolicyDecision ALLOWED = new PolicyDecision(true, "");

    public PolicyDecision {
        Objects.requireNonNull(policy, "policy");
    }

    public static PolicyDecision allow() {
        return ALLOWED;
    }

    public static PolicyDecision deny(String policy) {
        return new PolicyDecision(false, policy);
    }
}
