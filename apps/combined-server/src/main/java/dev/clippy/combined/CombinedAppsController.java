package dev.clippy.combined;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class CombinedAppsController {
    @GetMapping("/")
    public Map<String, Object> apps() {
        return Map.of(
                "name", "combined-server",
                "apps", Map.of(
                        "auth", "/auth",
                        "klippy", "/klippy",
                        "jarvis", "/jarvis",
                        "keeboarder", "/keeboarder"
                )
        );
    }
}
