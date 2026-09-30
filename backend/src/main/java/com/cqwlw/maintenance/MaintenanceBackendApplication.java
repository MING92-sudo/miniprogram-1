package com.cqwlw.maintenance;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.mybatis.spring.annotation.MapperScan;

@SpringBootApplication
@ConfigurationPropertiesScan
@MapperScan("com.cqwlw.maintenance.mapper")
public class MaintenanceBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(MaintenanceBackendApplication.class, args);
    }
}
