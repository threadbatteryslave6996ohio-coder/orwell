package dev.orwell.keeboarder.mac;

import dev.orwell.keeboarder.client.KeeboarderClientConfig;
import dev.orwell.env.http.EnvLoader;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws Exception {
        KeeboarderClientConfig config = KeeboarderClientConfig.fromEnv(
                EnvLoader.load("file"),
                KeeboarderClientConfig.defaultName("Mac-", "MacClient"));
        MacKeyboardClient client = new MacKeyboardClient(config);
        client.run();
    }
}
