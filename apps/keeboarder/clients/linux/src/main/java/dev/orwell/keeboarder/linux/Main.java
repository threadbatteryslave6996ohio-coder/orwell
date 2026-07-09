package dev.orwell.keeboarder.linux;

import dev.orwell.keeboarder.client.KeeboarderClientConfig;
import dev.orwell.env.http.EnvLoader;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws Exception {
        KeeboarderClientConfig config = KeeboarderClientConfig.fromEnv(
                EnvLoader.load("file"),
                KeeboarderClientConfig.defaultName("Linux-", "LinuxClient"));
        LinuxKeyboardClient client = new LinuxKeyboardClient(config);
        client.run();
    }
}
