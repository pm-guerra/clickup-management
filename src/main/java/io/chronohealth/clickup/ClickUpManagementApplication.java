package io.chronohealth.clickup;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ClickUpManagementApplication {

    public static void main(String[] args) {
        SpringApplication.run(ClickUpManagementApplication.class, args);
    }
}
