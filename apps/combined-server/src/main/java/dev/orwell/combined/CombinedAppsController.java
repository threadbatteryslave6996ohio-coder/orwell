package dev.orwell.combined;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Value;

import java.util.Map;

@RestController
public class CombinedAppsController {
    private final String secretsRoutePrefix;

    public CombinedAppsController(@Value("${secrets.route-prefix:/secrets}") String secretsRoutePrefix) {
        this.secretsRoutePrefix = secretsRoutePrefix;
    }

    @GetMapping("/")
    public Map<String, Object> apps() {
        return Map.of(
                "name", "combined-server",
                "apps", Map.of(
                        "auth", "/auth",
                        "klippy", "/klippy",
                        "jarvis", "/jarvis",
                        "keeboarder", "/keeboarder",
                        "secrets", secretsRoutePrefix
                )
        );
    }
}
