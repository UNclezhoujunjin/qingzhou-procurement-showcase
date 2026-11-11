package com.qingzhou.procurement;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

@Configuration @Profile("cloud")
class CloudSafetyConfig {
    @Bean static BeanFactoryPostProcessor cloudEnvironmentGuard(Environment environment) {
        return factory -> {
            String url=environment.getProperty("DB_URL","");
            if(!url.startsWith("jdbc:postgresql://"))throw new IllegalStateException("Cloud deployment requires DB_URL pointing to PostgreSQL; local H2 storage is not allowed.");
            String lower=url.toLowerCase(Locale.ROOT);
            if(!lower.matches(".*[?&]sslmode=(require|verify-ca|verify-full)(&.*)?$"))
                throw new IllegalStateException("Cloud PostgreSQL requires TLS: set sslmode=verify-full (or require) in DB_URL.");
            if(environment.getProperty("DB_USER","").isBlank()||environment.getProperty("DB_PASSWORD","").isBlank())
                throw new IllegalStateException("Set DB_USER and DB_PASSWORD as private deployment environment variables.");
            String password=environment.getProperty("BOOTSTRAP_ADMIN_PASSWORD","");
            if(password.length()<8||password.getBytes(StandardCharsets.UTF_8).length>72||password.equals("Qingzhou@2026"))
                throw new IllegalStateException("Set a new private BOOTSTRAP_ADMIN_PASSWORD with at least 8 characters and at most 72 UTF-8 bytes; do not use the demo password.");
            if(environment.getProperty("app.demo",Boolean.class,false))throw new IllegalStateException("Demo mode cannot be enabled in the cloud profile.");
        };
    }
}
