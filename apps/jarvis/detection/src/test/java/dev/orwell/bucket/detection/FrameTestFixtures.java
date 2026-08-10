package dev.orwell.bucket.detection;

import dev.orwell.bucket.detection.entity.FrameEventEntity;
import dev.orwell.bucket.detection.entity.FrameSubscriptionEntity;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.time.Instant;

/**
 * Test frames, and entities with database-assigned ids for the tests that mock their repositories.
 *
 * <p>Fan-out and delivery are keyed by id, so these fixtures need one without a round trip. The
 * field is set reflectively rather than adding a setter that production code would never call.
 */
final class FrameTestFixtures {
    static final int WIDTH = 320;
    static final int HEIGHT = 240;

    private FrameTestFixtures() {
    }

    /** A uniform gray frame. */
    static byte[] flat(int gray) {
        return encode(WIDTH, HEIGHT, (x, y) -> gray);
    }

    /** A uniform gray frame with the top-left quarter filled at a different level. */
    static byte[] withBlock(int background, int block) {
        return encode(WIDTH, HEIGHT, (x, y) -> x < WIDTH / 2 && y < HEIGHT / 2 ? block : background);
    }

    /** A smooth horizontal ramp — the same scene at any resolution. */
    static byte[] gradient(int width, int height) {
        return encode(width, height, (x, y) -> 255 * x / width);
    }

    static byte[] encode(int width, int height, Shader shader) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int value = Math.clamp(shader.gray(x, y), 0, 255);
                image.setRGB(x, y, (value << 16) | (value << 8) | value);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "jpg", out);
        } catch (Exception exception) {
            throw new IllegalStateException("unable to encode test frame", exception);
        }
        return out.toByteArray();
    }

    static FrameEventEntity frame(long id, String source, byte[] bytes) {
        return frame(id, source, bytes, Instant.parse("2026-08-10T12:00:00Z"));
    }

    static FrameEventEntity frame(long id, String source, byte[] bytes, Instant capturedAt) {
        FrameEventEntity frame =
                new FrameEventEntity(source, id, "sha-" + id, true, 0.25, capturedAt, bytes);
        setId(frame, id);
        return frame;
    }

    static FrameSubscriptionEntity subscription(
            long id, String clientId, String url, String source, long cursor) {
        FrameSubscriptionEntity subscription = new FrameSubscriptionEntity(
                clientId, url, source, cursor, Instant.parse("2026-08-10T12:00:00Z"));
        setId(subscription, id);
        return subscription;
    }

    private static void setId(Object entity, long id) {
        try {
            Field field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("unable to set id on " + entity.getClass(), exception);
        }
    }

    @FunctionalInterface
    interface Shader {
        int gray(int x, int y);
    }
}
