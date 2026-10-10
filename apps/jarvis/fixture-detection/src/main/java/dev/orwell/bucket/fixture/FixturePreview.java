package dev.orwell.bucket.fixture;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Optional;
import javax.imageio.ImageIO;

/**
 * Draws a fixture's regions onto the latest frame, so you can see what the detector is looking at.
 *
 * <p><strong>Without this the feature is unconfigurable.</strong> A region is four fractions, and
 * nobody can write four fractions that land on their own lamp by thinking about it. The realistic
 * alternative is guess, wait, read a number, guess again — with no way to tell "my threshold is
 * wrong" apart from "my rectangle is on the wrong wall". One picture with the box drawn on it
 * collapses that whole loop, which is why a read-only detector has a route that returns an image.
 *
 * <p>Green is the region, blue the reference. The current value and state are printed on it, so a
 * screenshot of this is a complete calibration record.
 */
final class FixturePreview {
    private static final Color REGION = new Color(0x3F, 0xE0, 0x5A);
    private static final Color REFERENCE = new Color(0x4C, 0x8D, 0xFF);
    private static final Color LABEL_BACKGROUND = new Color(0, 0, 0, 170);

    private FixturePreview() {
    }

    /**
     * The frame with the fixture's regions outlined, as a JPEG.
     *
     * @return empty if the frame cannot be decoded — the hub does not require a frame to be an
     *         image, so that is a normal answer rather than a fault.
     */
    static Optional<byte[]> render(byte[] frameBytes, FixtureWatch watch) {
        BufferedImage source;
        try {
            source = ImageIO.read(new ByteArrayInputStream(frameBytes));
        } catch (IOException exception) {
            return Optional.empty();
        }
        if (source == null) {
            return Optional.empty();
        }

        // Copied into a fresh TYPE_INT_RGB image rather than drawn on directly: a frame decoded from
        // a grayscale or CMYK JPEG has a colour model that will not take coloured strokes, and the
        // overlay would come out invisible instead of failing.
        BufferedImage canvas =
                new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = canvas.createGraphics();
        try {
            graphics.drawImage(source, 0, 0, null);
            Fixture fixture = watch.fixture();
            // Scaled to the frame, so the outline stays visible on a 4K frame and does not swallow a
            // 320px one.
            int stroke = Math.max(2, Math.min(source.getWidth(), source.getHeight()) / 200);
            graphics.setStroke(new BasicStroke(stroke));

            if (fixture.reference() != null) {
                outline(graphics, fixture.reference().inPixels(canvas.getWidth(), canvas.getHeight()),
                        REFERENCE);
            }
            outline(graphics, fixture.region().inPixels(canvas.getWidth(), canvas.getHeight()),
                    REGION);
            label(graphics, canvas, watch);
        } finally {
            graphics.dispose();
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(canvas, "jpg", out);
        } catch (IOException exception) {
            return Optional.empty();
        }
        return Optional.of(out.toByteArray());
    }

    private static void outline(Graphics2D graphics, Rectangle box, Color colour) {
        graphics.setColor(colour);
        graphics.drawRect(box.x, box.y, box.width - 1, box.height - 1);
    }

    private static void label(Graphics2D graphics, BufferedImage canvas, FixtureWatch watch) {
        Object value = watch.asJson().get("value");
        String text = "%s  %s  %s%s".formatted(
                watch.fixture().name(),
                watch.state().label(),
                value == null ? "no reading yet" : "%.3f".formatted(((Number) value).doubleValue()),
                value == null ? "" : " " + watch.fixture().metric().unit());

        int size = Math.max(11, canvas.getWidth() / 60);
        graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, size));
        int height = graphics.getFontMetrics().getHeight();
        int width = graphics.getFontMetrics().stringWidth(text);
        graphics.setColor(LABEL_BACKGROUND);
        graphics.fillRect(0, 0, Math.min(canvas.getWidth(), width + size), height + size / 2);
        graphics.setColor(Color.WHITE);
        graphics.drawString(text, size / 2, height);
    }
}
