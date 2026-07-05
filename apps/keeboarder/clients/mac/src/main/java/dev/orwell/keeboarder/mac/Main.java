package dev.orwell.keeboarder.mac;

import dev.orwell.env.EnvFiles;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws Exception {
        ClientConfig config = ClientConfig.fromEnv(EnvFiles.load());
        MacKeyboardClient client = new MacKeyboardClient(config);
        client.run();
    }
}
