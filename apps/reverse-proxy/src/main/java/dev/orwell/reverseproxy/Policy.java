package dev.orwell.reverseproxy;

/**
 * A single admission rule. {@link #pass(ProxyRequest)} answers whether the request may go
 * upstream; returning {@code false} makes the proxy answer {@code 403} with
 * {@link ProxyEndpoint#DENIED_MESSAGE} and never contact the upstream at all.
 *
 * <p>Add a policy by declaring another {@code Policy} bean — {@link PolicyChain} is built from
 * every {@code Policy} in the context, in declaration order, so no existing class changes.
 *
 * <p>Policies run on the request path: an implementation must be thread-safe and must not block.
 * One that throws is treated as a refusal, not as a pass — the chain fails closed.
 */
@FunctionalInterface
public interface Policy {
    boolean pass(ProxyRequest request);

    /** Name reported on the block log line and in {@code /health}. */
    default String name() {
        return getClass().getSimpleName();
    }
}
