package dev.orwell.bucket.proxy;

import dev.orwell.bootstrap.SpringServerBootstrap;
import dev.orwell.env.http.EnvLoader;

import java.io.IOException;

/** Owns process startup; the application class remains embeddable by a combined server. */
public final class BucketProxyLauncher {
    private BucketProxyLauncher() {
    }

    public static void main(String[] args) throws IOException {
        var env = JarvisProxyEnvs.from(EnvLoader.load("file"));
        SpringServerBootstrap.run(
                BucketProxyApplication.class,
                JarvisProxyEnvs.springProperties(env),
                "jarvisProxyLauncher");
    }
}
