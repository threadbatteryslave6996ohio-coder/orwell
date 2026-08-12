package dev.orwell.bucket.hub;

import dev.orwell.bucket.hub.entity.FrameEventEntity;

/**
 * The hub's half of the test fixtures: the part that needs a {@link FrameEventEntity}, and so
 * cannot live in {@code jarvis-frame-core} alongside the frame builders. Everything that only
 * builds bytes is in {@code dev.orwell.bucket.frame.FrameFixtures}.
 */
final class FrameTestFixtures {
    private FrameTestFixtures() {
    }

    /**
     * Gives an entity the id the database would have assigned. Streaming and replay are keyed by
     * id, so the tests that mock the repository need one without a round trip; the field is set
     * reflectively rather than adding a setter production code would never call.
     */
    static FrameEventEntity withId(FrameEventEntity frame, long id) {
        try {
            java.lang.reflect.Field field = FrameEventEntity.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(frame, id);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("unable to set id", exception);
        }
        return frame;
    }
}
