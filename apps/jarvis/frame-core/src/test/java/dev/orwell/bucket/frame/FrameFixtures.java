package dev.orwell.bucket.frame;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Synthetic frames, shared by every module that takes one.
 *
 * <p>Published as a test-jar because the hub and motion both need to build a frame and neither
 * depends on the other. It lives beside {@link FramePayload} for the same reason that does: the
 * envelope is the one thing all the frame services genuinely share, so its test companion is the
 * one place a frame builder can sit without inventing a dependency between siblings.
 */
public final class FrameFixtures {
    public static final int WIDTH = 320;
    public static final int HEIGHT = 240;

    private FrameFixtures() {
    }

    /** A uniform gray frame. */
    public static byte[] flat(int gray) {
        return encode(WIDTH, HEIGHT, (x, y) -> gray);
    }

    /** A uniform gray frame with the top-left quarter filled at a different level. */
    public static byte[] withBlock(int background, int block) {
        return encode(WIDTH, HEIGHT, (x, y) -> x < WIDTH / 2 && y < HEIGHT / 2 ? block : background);
    }

    /** A smooth horizontal ramp — the same scene at any resolution. */
    public static byte[] gradient(int width, int height) {
        return encode(width, height, (x, y) -> 255 * x / width);
    }

    public static byte[] encode(int width, int height, Shader shader) {
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

    /** The push body a producer sends: the same envelope for /frames, /detect and /motion. */
    public static Map<String, Object> request(String source, byte[] frame) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", source);
        payload.put("frameBase64", Base64.getEncoder().encodeToString(frame));
        return payload;
    }

    @FunctionalInterface
    public interface Shader {
        int gray(int x, int y);
    }
}
