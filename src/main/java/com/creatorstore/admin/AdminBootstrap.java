package com.creatorstore.admin;

import com.creatorstore.security.AdminRole;
import com.creatorstore.common.Normalizer;
import com.creatorstore.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the first SUPER_ADMIN on startup when the table is empty and
 * ADMIN_BOOTSTRAP_EMAIL / ADMIN_BOOTSTRAP_PASSWORD are set. Does nothing otherwise.
 * Remove those variables after the first start; further admins are created by a SUPER_ADMIN (future endpoint).
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final AdminUserRepository admins;
    private final PasswordEncoder passwordEncoder;
    private final AppProperties.Admin.Bootstrap bootstrap;

    public AdminBootstrap(AdminUserRepository admins, PasswordEncoder passwordEncoder, AppProperties props) {
        this.admins = admins;
        this.passwordEncoder = passwordEncoder;
        this.bootstrap = props.admin().bootstrap();
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (bootstrap == null || isBlank(bootstrap.email()) || isBlank(bootstrap.password())) {
            return;
        }
        if (admins.count() > 0) {
            log.info("Admin bootstrap skipped: admin_users is not empty");
            return;
        }
        if (bootstrap.password().length() < 12) {
            throw new IllegalStateException("ADMIN_BOOTSTRAP_PASSWORD must be at least 12 characters");
        }
        AdminUser admin = admins.save(new AdminUser(
                Normalizer.email(bootstrap.email()),
                Normalizer.fullName(bootstrap.fullName()),
                passwordEncoder.encode(bootstrap.password()),
                AdminRole.SUPER_ADMIN));
        log.warn("Created bootstrap SUPER_ADMIN {} (id {}). Remove ADMIN_BOOTSTRAP_* variables now.",
                admin.getEmail(), admin.getId());
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
