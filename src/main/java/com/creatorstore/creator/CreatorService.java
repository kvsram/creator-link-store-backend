package com.creatorstore.creator;

import com.creatorstore.common.ApiException;
import com.creatorstore.common.Normalizer;
import com.creatorstore.config.AppProperties;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The only entry point to the {@code creators} table for every other module.
 * Owns normalization, uniqueness errors, and the login-attempt counters.
 */
@Service
public class CreatorService {

    private static final Pattern USERNAME = Pattern.compile("^[a-z0-9_.]{3,30}$");
    private static final Pattern E164 = Pattern.compile("^\\+[1-9]\\d{7,14}$");

    /** Usernames that would collide with routes or look official. Extend as the public URL space grows. */
    private static final Set<String> RESERVED_USERNAMES = Set.of(
            "admin", "administrator", "api", "app", "auth", "login", "logout", "signup", "register",
            "support", "help", "about", "terms", "privacy", "www", "mail", "root", "system", "store");

    private final CreatorRepository creators;
    private final PasswordEncoder passwordEncoder;
    private final AppProperties.Login loginPolicy;

    public CreatorService(CreatorRepository creators, PasswordEncoder passwordEncoder, AppProperties props) {
        this.creators = creators;
        this.passwordEncoder = passwordEncoder;
        this.loginPolicy = props.login();
    }

    // ---- registration ----------------------------------------------------

    @Transactional(readOnly = true)
    public boolean isUsernameAvailable(String rawUsername) {
        String username = Normalizer.username(rawUsername);
        return USERNAME.matcher(username).matches()
                && !RESERVED_USERNAMES.contains(username)
                && !creators.existsByUsername(username);
    }

    /**
     * Creates the account. Input is normalized here; the unique constraints are the final referee
     * when two requests race, and their violation is translated into a clean 409.
     */
    @Transactional
    public Creator register(String rawFullName, String rawEmail, String rawUsername,
                            String rawPassword, String rawPhone) {
        String fullName = Normalizer.fullName(rawFullName);
        String email = Normalizer.email(rawEmail);
        String username = Normalizer.username(rawUsername);
        String phone = normalizePhoneOrThrow(rawPhone);

        if (RESERVED_USERNAMES.contains(username)) {
            throw ApiException.conflict("USERNAME_RESERVED", "This username is reserved.");
        }
        if (creators.existsByEmail(email)) {
            throw ApiException.conflict("EMAIL_TAKEN", "This email is already registered.");
        }
        if (creators.existsByUsername(username)) {
            throw ApiException.conflict("USERNAME_TAKEN", "This username is already taken.");
        }

        try {
            return creators.saveAndFlush(new Creator(
                    fullName, email, username, passwordEncoder.encode(rawPassword), phone));
        } catch (DataIntegrityViolationException ex) {
            throw mapConstraintViolation(ex);
        }
    }

    // ---- lookups ---------------------------------------------------------

    @Transactional(readOnly = true)
    public Creator getById(Long id) {
        return creators.findById(id)
                .orElseThrow(() -> ApiException.notFound("CREATOR_NOT_FOUND", "Creator not found."));
    }

    /** The login form has one field. An "@" means email, otherwise username (usernames cannot contain "@"). */
    @Transactional(readOnly = true)
    public Optional<Creator> findByLoginIdentifier(String rawIdentifier) {
        String identifier = rawIdentifier.trim();
        if (identifier.contains("@")) {
            return creators.findByEmail(Normalizer.email(identifier));
        }
        return creators.findByUsername(Normalizer.username(identifier));
    }

    // ---- login bookkeeping (each in its own transaction, so a failed login is still recorded) ----

    @Transactional
    public void recordFailedLogin(Long creatorId, Instant now) {
        creators.recordFailedLogin(creatorId, loginPolicy.maxFailedAttempts(), now.plus(loginPolicy.lockDuration()));
    }

    @Transactional
    public void recordSuccessfulLogin(Long creatorId, Instant now) {
        creators.recordSuccessfulLogin(creatorId, now);
    }

    // ---- phone -----------------------------------------------------------

    @Transactional
    public Creator changePhoneNumber(Long creatorId, String rawPhone) {
        String phone = normalizePhoneOrThrow(rawPhone);
        if (phone == null) {
            throw ApiException.badRequest("PHONE_REQUIRED", "A phone number is required.");
        }
        Creator creator = getById(creatorId);
        creator.changePhoneNumber(phone);
        return creator;
    }

    @Transactional
    public void markPhoneVerified(Long creatorId, String phoneNumberOnLink, Instant now) {
        Creator creator = getById(creatorId);
        if (!phoneNumberOnLink.equals(creator.getPhoneNumber())) {
            throw ApiException.badRequest("PHONE_CHANGED",
                    "The phone number changed after this link was sent. Please request a new link.");
        }
        creator.markPhoneVerified(now);
    }

    // ---- helpers ---------------------------------------------------------

    private static String normalizePhoneOrThrow(String rawPhone) {
        String phone = Normalizer.phone(rawPhone);
        if (phone != null && !E164.matcher(phone).matches()) {
            throw ApiException.badRequest("INVALID_PHONE",
                    "Phone number must include the country code, for example +919876543210.");
        }
        return phone;
    }

    private static ApiException mapConstraintViolation(DataIntegrityViolationException ex) {
        String cause = String.valueOf(ex.getMostSpecificCause().getMessage());
        if (cause.contains("uq_creators_email")) {
            return ApiException.conflict("EMAIL_TAKEN", "This email is already registered.");
        }
        if (cause.contains("uq_creators_username")) {
            return ApiException.conflict("USERNAME_TAKEN", "This username is already taken.");
        }
        return ApiException.conflict("SIGNUP_CONFLICT", "Could not create the account. Please try again.");
    }
}
