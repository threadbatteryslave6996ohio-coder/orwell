package dev.orwell.bucket.detection;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

/**
 * Reduces a frame to a fixed grayscale fingerprint and compares two consecutive fingerprints.
 *
 * <p>The fingerprint, not the frame, is what gets held between requests. That choice does three
 * things at once: state per source is a fixed ~1 KB no matter the stream resolution; the
 * comparison is resolution-independent, so a stream that changes size still yields comparable
 * grids; and box-averaging each cell absorbs the JPEG and sensor noise that a raw pixel diff
 * would keep reporting as motion on a completely static scene.
 */
final class FrameChangeDetector {
    static final int GRID = 16;
    static final int CELLS = GRID * GRID;
    /** Samples taken per cell per axis, so the cost of a frame is bounded by the grid, not by its resolution. */
    private static final int SAMPLES_PER_AXIS = 8;

    private final int cellThreshold;
    private final double minChangedFraction;

    FrameChangeDetector(int cellThreshold, double minChangedFraction) {
        this.cellThreshold = cellThreshold;
        this.minChangedFraction = minChangedFraction;
    }

    /**
     * Box-averaged {@link #GRID}x{@link #GRID} luminance grid for an encoded frame.
     *
     * @throws FramePayload.InvalidFrameException if the bytes are not a decodable image — that is
     *         a bad frame from the caller, so it maps to 400 rather than 500.
     */
    static int[] fingerprint(byte[] frameBytes) {
        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(frameBytes));
        } catch (Exception exception) {
            throw new FramePayload.InvalidFrameException("unable to decode frame");
        }
        if (image == null) {
            throw new FramePayload.InvalidFrameException("unable to decode frame");
        }

        int width = image.getWidth();
        int height = image.getHeight();
        int[] cells = new int[CELLS];
        for (int row = 0; row < GRID; row++) {
            int top = (int) ((long) row * height / GRID);
            int bottom = Math.max(top + 1, (int) ((long) (row + 1) * height / GRID));
            for (int column = 0; column < GRID; column++) {
                int left = (int) ((long) column * width / GRID);
                int right = Math.max(left + 1, (int) ((long) (column + 1) * width / GRID));
                cells[row * GRID + column] = averageLuminance(image, left, top, right, bottom);
            }
        }
        return cells;
    }

    Comparison compare(int[] previous, int[] current) {
        int changedCells = 0;
        for (int cell = 0; cell < CELLS; cell++) {
            if (Math.abs(previous[cell] - current[cell]) > cellThreshold) {
                changedCells++;
            }
        }
        double changedFraction = (double) changedCells / CELLS;
        return new Comparison(changedFraction >= minChangedFraction, changedCells, changedFraction);
    }

    private static int averageLuminance(BufferedImage image, int left, int top, int right, int bottom) {
        int width = image.getWidth();
        int height = image.getHeight();
        int stepX = Math.max(1, (right - left) / SAMPLES_PER_AXIS);
        int stepY = Math.max(1, (bottom - top) / SAMPLES_PER_AXIS);
        long sum = 0;
        int samples = 0;
        for (int y = top; y < bottom && y < height; y += stepY) {
            for (int x = left; x < right && x < width; x += stepX) {
                int rgb = image.getRGB(x, y);
                int red = (rgb >> 16) & 0xFF;
                int green = (rgb >> 8) & 0xFF;
                int blue = rgb & 0xFF;
                sum += (299L * red + 587L * green + 114L * blue) / 1000L;
                samples++;
            }
        }
        return samples == 0 ? 0 : (int) (sum / samples);
    }

    /** Outcome of one frame-to-frame comparison. */
    record Comparison(boolean changed, int changedCells, double changedFraction) {
    }
}
