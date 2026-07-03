package com.keeboarder.mac;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws Exception {
        ClientConfig config = ClientConfig.fromArgs(args);
        MacKeyboardClient client = new MacKeyboardClient(config);
        client.run();
    }
}
