package dev.clippy.bucket.proxy;

import jakarta.servlet.MultipartConfigElement;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MultipartConfiguration {
    @Bean
    MultipartConfigElement multipartConfigElement(ProxyProperties properties) {
        long limit = properties.storage().maxFileSize();
        return new MultipartConfigElement("", limit, limit, 0);
    }
}
