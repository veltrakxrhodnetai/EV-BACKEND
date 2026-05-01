package com.evcsms.backend;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.context.annotation.Bean;

import java.util.TimeZone;

@SpringBootApplication(scanBasePackages = {"com.evcsms"})
@EntityScan(basePackages = {"com.evcsms.backend.model", "com.evcsms.model"})
@EnableJpaRepositories(basePackages = {"com.evcsms.backend.repository", "com.evcsms.repository"})
public class EvCsmsApplication {

    private static final Logger logger = LoggerFactory.getLogger(EvCsmsApplication.class);

    public static void main(String[] args) {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"));
        SpringApplication.run(EvCsmsApplication.class, args);
    }

    @Bean
    CommandLineRunner commandLineRunner() {
        return args -> {
            logger.info("EV CSMS backend application started successfully.");
            logger.info("Health check: application is UP.");
        };
    }
}
