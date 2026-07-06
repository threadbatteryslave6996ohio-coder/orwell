package dev.orwell.keeboarder.mac;

import dev.orwell.env.http.EnvLoader;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws Exception {
        ClientConfig config = ClientConfig.fromEnv(EnvLoader.load("file"));
        MacKeyboardClient client = new MacKeyboardClient(config);
        client.run();
    }
}
