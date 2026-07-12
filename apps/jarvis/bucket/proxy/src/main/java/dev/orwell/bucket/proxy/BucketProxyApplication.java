package dev.orwell.bucket.proxy;

import dev.orwell.bootstrap.SpringServerBootstrap;
import dev.orwell.bootstrap.HealthDetailsProvider;
import dev.orwell.env.Env;
import dev.orwell.bucket.proxy.storage.BucketStorage;
import dev.orwell.logging.CustomLogger;
import dev.orwell.logging.Logger;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

import java.util.Map;

@SpringBootApplication
@ConfigurationPropertiesScan
public class BucketProxyApplication {
    public static ConfigurableApplicationContext start(Env env) {
        return SpringServerBootstrap.run(
                BucketProxyApplication.class,
                JarvisProxyEnvs.springProperties(env),
                "jarvisProxyLauncher");
    }

    public static ConfigurableApplicationContext start(Map<String, String> environment) {
        return start(JarvisProxyEnvs.from(environment));
    }

    /** Custom {@link Logger} available for injection across the bucket proxy. */
    @Bean
    public Logger logger() {
        return new CustomLogger("bucket-proxy");
    }

    @Bean(name = "jarvisHealthDetailsProvider")
    public HealthDetailsProvider healthDetailsProvider(ProxyProperties properties, BucketStorage storage) {
        return () -> Map.of(
                "bucket", storage.containerName(),
                "region", storage.location(),
                "auth", "external-auth-server",
                "authServer", properties.authServer().baseUrl()
        );
    }
}
