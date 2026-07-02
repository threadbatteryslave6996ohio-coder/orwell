package dev.clippy.clients.linux.clipboard;

import java.nio.file.Files;
import java.nio.file.Path;

final class Executables {
    private Executables() {
    }

    static boolean exists(String name) {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return false;
        }
        for (String directory : path.split(":")) {
            if (Files.isExecutable(Path.of(directory, name))) {
                return true;
            }
        }
        return false;
    }
}
