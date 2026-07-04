package dev.orwell.combined;

import dev.orwell.keeboarder.server.KeeboarderController;
import dev.orwell.keeboarder.server.KeeboarderServerConfiguration;
import dev.orwell.keeboarder.server.KeeboarderService;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@Import({KeeboarderServerConfiguration.class, KeeboarderController.class, KeeboarderService.class})
class CombinedKeeboarderModuleConfiguration {
}
