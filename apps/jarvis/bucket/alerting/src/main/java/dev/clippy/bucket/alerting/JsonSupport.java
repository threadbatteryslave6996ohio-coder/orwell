package dev.clippy.bucket.alerting;

import com.fasterxml.jackson.databind.ObjectMapper;

final class JsonSupport {
    static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonSupport() {
    }
}
