package dev.orwell.reverseproxy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

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

    /**
     * What was in force, in a form an audit row can be grouped by: each policy's name with a short
     * digest of its {@link Policy#signature()}, in evaluation order —
     * {@code pattern-policy:1a2b3c4d}.
     *
     * <p>Names alone would not do. Two proxies running {@code PatternPolicy} with different
     * patterns, or the same proxy before and after someone edited {@code PROXY_BLOCKED_PATTERNS},
     * both write {@code pattern-policy} — so "was this allowed under today's rules or yesterday's?"
     * could not be asked of the table. The digest changes when the configuration does.
     *
     * <p>Digested rather than stored whole because a signature can be long: the whole blocklist on
     * every row would dominate the table. The digest is not a secret and does not need to resist
     * anything — it only has to differ when the input differs, which is why a truncated SHA-256 is
     * enough. An empty chain fingerprints as {@code ""}, an open pass-through being a fact worth
     * recording rather than an absence.
     */
    public String fingerprint() {
        return policies.stream()
                .map(policy -> policy.name() + ":" + digest(policy.signature()))
                .collect(Collectors.joining(","));
    }

    private static String digest(String signature) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(signature.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 4);
        } catch (NoSuchAlgorithmException exception) {
            // SHA-256 is required of every JVM, so this cannot happen — and if it somehow did, an
            // audit trail that cannot say what was in force is not worth failing a request over.
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
