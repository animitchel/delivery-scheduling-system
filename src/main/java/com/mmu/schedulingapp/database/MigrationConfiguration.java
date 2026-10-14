package com.mmu.schedulingapp.database;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "spring.flyway.enabled", havingValue = "true")
public class MigrationConfiguration {
    @Bean
    FlywayMigrationStrategy controlledMigration(Environment environment) {
        return flyway -> {
            if (!"validate".equals(environment.getProperty("spring.jpa.hibernate.ddl-auto"))) {
                throw new IllegalStateException("Flyway-managed databases require spring.jpa.hibernate.ddl-auto=validate.");
            }
            var configuration = flyway.getConfiguration();
            if (configuration.isBaselineOnMigrate() || !configuration.isCleanDisabled()
                    || !configuration.isValidateOnMigrate()) {
                throw new IllegalStateException("Automatic baseline/clean and disabled migration validation are prohibited.");
            }
            flyway.migrate();
        };
    }
}
