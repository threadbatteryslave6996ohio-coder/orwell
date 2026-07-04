package dev.orwell.clients.core;

@FunctionalInterface
public interface ClipboardReader {
    String readText() throws Exception;

    default String name() {
        return "clipboard";
    }
}
