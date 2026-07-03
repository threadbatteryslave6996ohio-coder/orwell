package dev.clippy.bucket.detection;

import java.util.List;

interface PersonDetector {
    List<Detection> detect(byte[] frameBytes);
}
