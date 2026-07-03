package dev.clippy.bucket.proxy;

import org.springframework.boot.SpringApplication;

/** Owns process startup; the application class remains embeddable by a combined server. */
public final class BucketProxyLauncher {
    private BucketProxyLauncher() {
    }

    public static void main(String[] args) {
        SpringApplication.run(BucketProxyApplication.class, args);
    }
}
