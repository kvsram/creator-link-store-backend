package com.creatorstore.local;

import com.creatorstore.creator.Creator;
import com.creatorstore.creator.CreatorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Seeds one demo creator on startup so a fresh local environment has an account to log in with.
 *
 * Runs only under the {@code local} profile. It goes through {@link CreatorService#register} rather than
 * inserting SQL, so the seeded row is normalised, hashed and validated exactly like a real signup and
 * cannot drift from the production path. Idempotent: a second start finds the account and does nothing.
 *
 * The admin account is not seeded here; {@code AdminBootstrap} already creates it from
 * ADMIN_BOOTSTRAP_EMAIL / ADMIN_BOOTSTRAP_PASSWORD.
 */
@Component
@Profile("local")
public class DevSeed implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevSeed.class);

    private final CreatorService creators;
    private final String username;
    private final String password;

    public DevSeed(CreatorService creators,
                   @Value("${app.seed.creator-username:demo}") String username,
                   @Value("${app.seed.creator-password:demo-password-123}") String password) {
        this.creators = creators;
        this.username = username;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (creators.findByLoginIdentifier(username).isPresent()) {
            log.info("Dev seed skipped: creator '{}' already exists", username);
            return;
        }
        Creator demo = creators.register("Demo Creator", username + "@example.test", username, password, "+919999999999");
        // The phone is marked verified directly: there is no SMS to click in a seed.
        creators.markPhoneVerified(demo.getId(), demo.getPhoneNumber(), Instant.now());
        log.warn("Dev seed: created creator '{}' (id {}). Password is app.seed.creator-password (SEED_CREATOR_PASSWORD).",
                demo.getUsername(), demo.getId());
    }
}
