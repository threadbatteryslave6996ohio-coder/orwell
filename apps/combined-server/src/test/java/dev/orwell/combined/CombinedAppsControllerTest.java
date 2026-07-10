package dev.orwell.combined;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CombinedAppsControllerTest {
    @Test
    void usesConfiguredSecretsRoutePrefixInRegistry() {
        CombinedAppsController controller = new CombinedAppsController("/vault");

        Map<String, Object> response = controller.apps();
        @SuppressWarnings("unchecked")
        Map<String, Object> apps = (Map<String, Object>) response.get("apps");

        assertThat(response).containsEntry("name", "combined-server");
        assertThat(response).containsKey("apps");
        assertThat(apps).containsEntry("secrets", "/vault");
    }
}
