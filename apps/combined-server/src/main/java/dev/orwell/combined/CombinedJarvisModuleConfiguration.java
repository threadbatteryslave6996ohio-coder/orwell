package dev.orwell.combined;

import dev.orwell.bucket.proxy.BucketProxyApplication;
import dev.orwell.bucket.proxy.ProxyController;
import dev.orwell.bucket.proxy.ProxyProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

@Configuration
@EnableConfigurationProperties(ProxyProperties.class)
@ComponentScan(
        basePackageClasses = ProxyController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = BucketProxyApplication.class
        )
)
class CombinedJarvisModuleConfiguration {
}
