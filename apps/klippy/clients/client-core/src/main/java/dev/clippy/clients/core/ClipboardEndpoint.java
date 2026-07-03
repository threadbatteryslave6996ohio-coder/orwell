package dev.clippy.clients.core;

import java.net.URI;
import java.util.Objects;

public final class ClipboardEndpoint {
    private ClipboardEndpoint() {
    }

    public static URI from(String remoteUrl) {
        String value = Objects.requireNonNull(remoteUrl, "remoteUrl").trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("remoteUrl must not be blank.");
        }
        return URI.create(value.endsWith("/clipboard")
                ? value
                : value.replaceAll("/+$", "") + "/clipboard");
    }
}
