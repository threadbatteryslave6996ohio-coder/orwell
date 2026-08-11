package dev.orwell.bucket.detection;

import dev.orwell.bucket.detection.entity.FrameEventEntity;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** Synthetic frames, shared by every test that needs one. */
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

    /** The push body a producer sends to {@code POST /frames}. */
    static Map<String, Object> request(String source, byte[] frame) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", source);
        payload.put("frameBase64", Base64.getEncoder().encodeToString(frame));
        return payload;
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

    @FunctionalInterface
    interface Shader {
        int gray(int x, int y);
    }
}
