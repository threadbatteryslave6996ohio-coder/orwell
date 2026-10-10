package dev.orwell.bucket.fixture;

import java.awt.Rectangle;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A rectangle of the frame, in fractions of the frame rather than pixels.
 *
 * <p><strong>Normalised on purpose.</strong> A region is configuration somebody typed once after
 * looking at a preview, and it has to survive the things that change underneath it: a camera
 * reconfigured from 1080p to 720p, a producer that starts downscaling before it pushes, a phone
 * held in a different orientation. Pixel coordinates silently point somewhere else after any of
 * those, and the failure looks like a lamp that stopped being detected rather than like a
 * misconfiguration. Fractions point at the same part of the scene whatever the resolution.
 *
 * <p>It does not survive the camera being <em>moved</em>. Nothing here can — that is what the
 * preview route is for.
 *
 * @param x      left edge, 0 at the left of the frame, 1 at the right
 * @param y      top edge, 0 at the top
 * @param width  fraction of the frame's width
 * @param height fraction of the frame's height
 */
public record Region(double x, double y, double width, double height) {

    public Region {
        require(x >= 0 && x <= 1, "x must be between 0 and 1, got " + x);
        require(y >= 0 && y <= 1, "y must be between 0 and 1, got " + y);
        require(width > 0 && width <= 1, "width must be above 0 and at most 1, got " + width);
        require(height > 0 && height <= 1, "height must be above 0 and at most 1, got " + height);
        require(x + width <= 1.0000001, "x + width must not exceed 1, got " + (x + width));
        require(y + height <= 1.0000001, "y + height must not exceed 1, got " + (y + height));
    }

    /**
     * This region in pixels for a frame of the given size, clamped inside it.
     *
     * <p>Always at least one pixel wide and tall: a region small enough to round to zero would
     * otherwise divide by zero when the mean is taken, and a fixture configured with a tiny region
     * should read a value rather than throw on every frame.
     */
    public Rectangle inPixels(int frameWidth, int frameHeight) {
        int left = clamp((int) Math.round(x * frameWidth), 0, Math.max(0, frameWidth - 1));
        int top = clamp((int) Math.round(y * frameHeight), 0, Math.max(0, frameHeight - 1));
        int right = clamp((int) Math.round((x + width) * frameWidth), left + 1, frameWidth);
        int bottom = clamp((int) Math.round((y + height) * frameHeight), top + 1, frameHeight);
        return new Rectangle(left, top, right - left, bottom - top);
    }

    public Map<String, Object> asJson() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("x", x);
        json.put("y", y);
        json.put("width", width);
        json.put("height", height);
        return json;
    }

    private static int clamp(int value, int low, int high) {
        return Math.max(low, Math.min(high, value));
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
