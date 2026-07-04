package dev.orwell.bucket.detection;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

final class HogPersonDetector implements PersonDetector {
    private final double minConfidence;

    HogPersonDetector(double minConfidence) {
        this.minConfidence = minConfidence;
    }

    @Override
    public List<Detection> detect(byte[] frameBytes) {
        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(frameBytes));
        } catch (Exception exception) {
            throw new IllegalArgumentException("unable to decode frame", exception);
        }
        if (image == null) {
            throw new IllegalArgumentException("unable to decode frame");
        }

        int width = image.getWidth();
        int height = image.getHeight();
        if (width < 32 || height < 32) {
            return List.of();
        }

        double average = averageBrightness(image);
        double center = averageBrightness(image.getSubimage(width / 4, height / 4, width / 2, height / 2));
        double confidence = Math.min(1.0, Math.max(0.0, (center - average + 128.0) / 255.0));
        if (confidence < minConfidence || confidence < 0.35) {
            return List.of();
        }

        List<Detection> detections = new ArrayList<>();
        detections.add(new Detection(width / 4, height / 4, width / 2, height / 2, confidence));
        return detections;
    }

    private static double averageBrightness(BufferedImage image) {
        long total = 0;
        long pixels = (long) image.getWidth() * image.getHeight();
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                total += (r + g + b) / 3;
            }
        }
        return pixels == 0 ? 0.0 : (double) total / pixels;
    }
}
