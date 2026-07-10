package dev.orwell.combined;

import dev.orwell.keeboarder.server.http.KeeboarderController;
import dev.orwell.keeboarder.server.config.KeeboarderServerConfiguration;
import dev.orwell.keeboarder.server.service.KeeboarderService;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@Import({KeeboarderServerConfiguration.class, KeeboarderController.class, KeeboarderService.class})
class CombinedKeeboarderModuleConfiguration {
}
