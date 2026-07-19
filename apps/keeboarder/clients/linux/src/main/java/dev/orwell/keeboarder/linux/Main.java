package dev.orwell.keeboarder.linux;

import dev.orwell.keeboarder.client.KeeboarderClientConfig;
import dev.orwell.env.http.EnvLoader;
import dev.orwell.logging.ConsoleLogger;
import dev.orwell.logging.Logger;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws Exception {
        Logger logger = new ConsoleLogger("keeboarder-linux-client");
        KeeboarderClientConfig config = KeeboarderClientConfig.fromEnv(
                EnvLoader.load("file"),
                KeeboarderClientConfig.defaultName("Linux-", "LinuxClient"));
        LinuxKeyboardClient client = new LinuxKeyboardClient(config, logger);
        client.run();
    }
}
