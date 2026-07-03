package dev.clippy.combined;

import com.keeboarder.server.KeeboarderController;
import com.keeboarder.server.KeeboarderServerConfiguration;
import com.keeboarder.server.KeeboarderService;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@Import({KeeboarderServerConfiguration.class, KeeboarderController.class, KeeboarderService.class})
class CombinedKeeboarderModuleConfiguration {
}
