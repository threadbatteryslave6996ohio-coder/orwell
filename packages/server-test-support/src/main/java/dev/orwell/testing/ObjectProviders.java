package dev.orwell.testing;

import org.springframework.beans.factory.ObjectProvider;

import java.util.Iterator;
import java.util.List;

/**
 * Builds an {@link ObjectProvider} around a value already in hand, for a controller test that
 * constructs its controller directly rather than through a Spring context.
 *
 * <p>The repo's request-scoped beans — {@code AuthenticationContext} above all — are injected as
 * {@code ObjectProvider<T>} so a controller can be built once and still see the current request's
 * value. A test that calls {@code new SomeController(...)} has to supply one, and the interface has
 * five methods to satisfy for what is conceptually a constant. Hand-writing that stub is how three
 * modules ended up with the same thirty lines.
 *
 * <pre>{@code
 * var controller = new KeeboarderController(new KeeboarderService(),
 *         ObjectProviders.of(AuthenticationContext.authenticated("client")));
 * }</pre>
 */
public final class ObjectProviders {

    private ObjectProviders() {
    }

    /**
     * Returns a provider that answers {@code value} to every lookup, available and unique.
     *
     * <p>An absent <em>identity</em> is not modelled by an absent bean here — the repo's
     * controllers are handed {@code AuthenticationContext.unauthenticated()} for that, and the
     * request-scoped bean is always present. A {@code null} value is tolerated (every lookup then
     * answers null, and the iterator is empty) rather than supported: Spring's own contract has
     * {@code getObject()} throw when there is no bean, so a test that needs to model a missing
     * bean faithfully should not use this.
     */
    public static <T> ObjectProvider<T> of(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return value;
            }

            @Override
            public T getObject() {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getIfUnique() {
                return value;
            }

            @Override
            public Iterator<T> iterator() {
                return value == null ? List.<T>of().iterator() : List.of(value).iterator();
            }
        };
    }
}
