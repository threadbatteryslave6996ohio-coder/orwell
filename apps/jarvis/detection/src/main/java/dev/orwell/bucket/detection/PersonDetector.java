package dev.orwell.bucket.detection;

import java.util.List;

interface PersonDetector {
    List<Detection> detect(byte[] frameBytes);
}
