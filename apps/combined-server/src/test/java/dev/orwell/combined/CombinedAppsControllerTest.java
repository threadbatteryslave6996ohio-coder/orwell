package dev.orwell.combined;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CombinedAppsControllerTest {
    @Test
    void usesConfiguredSecretsRoutePrefixInRegistry() {
        CombinedAppsController controller = new CombinedAppsController("/vault");

        Map<String, Object> response = controller.apps();

        assertThat(response).containsEntry("name", "combined-server");
        assertThat(response).containsKey("apps");
        assertThat((Map<?, ?>) response.get("apps")).containsEntry("secrets", "/vault");
    }
}
