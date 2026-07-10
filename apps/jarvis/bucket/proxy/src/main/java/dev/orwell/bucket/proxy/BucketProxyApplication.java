package dev.orwell.bucket.proxy;

import dev.orwell.bootstrap.SpringServerBootstrap;
import dev.orwell.env.Env;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ConfigurableApplicationContext;

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
}
