package com.cqwlw.maintenance;

import com.cqwlw.maintenance.config.PlatformProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(PlatformProperties.class)
public class MaintenanceBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(MaintenanceBackendApplication.class, args);
    }
}
