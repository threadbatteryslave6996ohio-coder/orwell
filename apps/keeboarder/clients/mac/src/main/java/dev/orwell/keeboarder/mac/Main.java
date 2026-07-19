package dev.orwell.keeboarder.mac;

import dev.orwell.keeboarder.client.KeeboarderClientConfig;
import dev.orwell.env.http.EnvLoader;
import dev.orwell.logging.ConsoleLogger;
import dev.orwell.logging.Logger;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws Exception {
        Logger logger = new ConsoleLogger("keeboarder-mac-client");
        KeeboarderClientConfig config = KeeboarderClientConfig.fromEnv(
                EnvLoader.load("file"),
                KeeboarderClientConfig.defaultName("Mac-", "MacClient"));
        MacKeyboardClient client = new MacKeyboardClient(config, logger);
        client.run();
    }
}
