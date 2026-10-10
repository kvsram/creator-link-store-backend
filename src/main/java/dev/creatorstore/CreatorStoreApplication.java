package dev.creatorstore;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

// Authentication is supplied exclusively by SessionAuthenticationFilter and the opaque session
// store. Do not create Spring Boot's generated in-memory user as an unused second auth mechanism.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableScheduling
public class CreatorStoreApplication {
  public static void main(String[] args) {
    SpringApplication.run(CreatorStoreApplication.class, args);
  }
}
