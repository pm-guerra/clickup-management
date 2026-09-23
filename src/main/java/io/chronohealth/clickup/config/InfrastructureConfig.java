package io.chronohealth.clickup.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class InfrastructureConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
