package dev.orwell.insta.apify;

/**
 * An actor run did not produce usable dataset items.
 *
 * <p>{@link Kind} exists because these failures need different answers, not a shared 502: a
 * timeout should make the caller ask for less, an exhausted Apify balance is an operations
 * problem that a person has to fix, and a rejected token is a misconfiguration. Collapsing them
 * into one status sends whoever is on call to debug the actor when the real fix is topping up the
 * account.
 */
public class ApifyException extends RuntimeException {

    public enum Kind {
        /** The run outlived the budget we gave it. Retrying with a smaller limit can work. */
        TIMED_OUT,
        /** The Apify account is out of usage credit — on the free plan, the $5 monthly grant. */
        OUT_OF_CREDIT,
        /** Apify is throttling us. */
        RATE_LIMITED,
        /** Apify would not accept {@code APIFY_TOKEN}. */
        TOKEN_REJECTED,
        /** Everything else: a failed actor, an unreachable API, an unreadable response. */
        UNAVAILABLE
    }

    private final Kind kind;

    public ApifyException(String message, Kind kind) {
        super(message);
        this.kind = kind;
    }

    public ApifyException(String message, Kind kind, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    public boolean timedOut() {
        return kind == Kind.TIMED_OUT;
    }
}
