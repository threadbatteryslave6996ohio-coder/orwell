package dev.orwell.bucket.proxy;

import dev.orwell.bootstrap.AppServer;

/** Owns process startup; the application class remains embeddable by a combined server. */
public final class BucketProxyLauncher {
    private BucketProxyLauncher() {
    }

    public static void main(String[] args) {
        new AppServer(JarvisProxyEnvs.ENV, BucketProxyApplication::start)
                .runOrExit(args);
    }
}
