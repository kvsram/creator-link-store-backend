package dev.creatorstore;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CreatorStoreApplication {
  public static void main(String[] args) {
    SpringApplication.run(CreatorStoreApplication.class, args);
  }
}
