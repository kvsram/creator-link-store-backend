package com.creatorstore;

import com.creatorstore.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
@EnableScheduling
public class CreatorStoreApplication {

    public static void main(String[] args) {
        SpringApplication.run(CreatorStoreApplication.class, args);
    }
}
