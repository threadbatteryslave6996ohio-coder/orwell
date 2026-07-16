package dev.orwell.bootstrap.launch;

import java.io.IOException;
import java.util.Map;

@FunctionalInterface
interface SpringEnvLoader {
    Map<String, String> load(String[] args) throws IOException;
}
