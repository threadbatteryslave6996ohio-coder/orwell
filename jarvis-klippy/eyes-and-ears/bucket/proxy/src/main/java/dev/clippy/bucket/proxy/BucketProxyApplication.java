package dev.clippy.bucket.proxy;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class BucketProxyApplication {
    public static void main(String[] args) {
        SpringApplication.run(BucketProxyApplication.class, args);
    }
}
