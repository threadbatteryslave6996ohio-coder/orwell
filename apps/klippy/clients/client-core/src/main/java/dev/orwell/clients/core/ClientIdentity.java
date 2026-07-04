package dev.orwell.clients.core;

import java.net.InetAddress;
import java.util.UUID;

public final class ClientIdentity {
    private ClientIdentity() {
    }

    public static String hostnameOrRandom(String randomPrefix) {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception ignored) {
            return randomPrefix + UUID.randomUUID();
        }
    }
}
