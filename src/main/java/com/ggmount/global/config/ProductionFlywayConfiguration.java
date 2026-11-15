package com.ggmount.global.config;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
public class ProductionFlywayConfiguration {
    // Flyway 자동 설정이 비활성화되어도 빈 생성 전에 환경 검사를 수행합니다.
    @Bean
    static BeanFactoryPostProcessor productionFlywayEnvironmentCheck(Environment environment) {
        return beanFactory -> ProductionFlywayGuard.checkEnvironment(environment);
    }

    @Bean
    @Primary
    FlywayMigrationStrategy productionFlywayMigrationStrategy(Environment environment) {
        return flyway -> {
            ProductionFlywayGuard.checkEnvironment(environment);
            if (ProductionFlywayGuard.isProduction(environment)) {
                new ProductionFlywayGuard().check(flyway,
                    environment.matchesProfiles("prod-rds-v9001"));
            }
            flyway.migrate();
        };
    }
}
