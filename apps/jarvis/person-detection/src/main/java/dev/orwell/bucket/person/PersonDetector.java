package dev.orwell.bucket.person;

import java.util.List;

interface PersonDetector {
    List<Detection> detect(byte[] frameBytes);
}
