package dev.orwell.loganalyzer;

import java.time.Instant;
import java.util.Map;

record LogLine(Instant timestamp, Map<String, Object> labels, String line) {
}
