package dev.creatorstore.service;

import dev.creatorstore.dto.RegisterRequest;
import dev.creatorstore.identity.SessionService;
import dev.creatorstore.repository.CreatorRepository;
import dev.creatorstore.repository.FeatureRepository;
import dev.creatorstore.repository.StoreRepository;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AuthenticationService {
  private static final Pattern HANDLE = Pattern.compile("[a-zA-Z0-9_]{3,40}");
  private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
  private static final Pattern PHONE = Pattern.compile("^[+()0-9 .-]{7,32}$");
  private static final Set<String> RESERVED_HANDLES = Set.of(
      "about", "admin", "api", "assets", "dashboard", "features", "health", "help",
      "login", "logout", "pricing", "privacy", "register", "settings", "signup",
      "static", "support", "terms", "www");

  private final CreatorRepository creators;
  private final StoreRepository stores;
  private final FeatureRepository features;
  private final BCryptPasswordEncoder passwordEncoder;
  private final SessionService sessions;

  public AuthenticationService(CreatorRepository creators, StoreRepository stores,
                               FeatureRepository features, BCryptPasswordEncoder passwordEncoder,
                               SessionService sessions) {
    this.creators = creators;
    this.stores = stores;
    this.features = features;
    this.passwordEncoder = passwordEncoder;
    this.sessions = sessions;
  }

  public Map<String, Object> uniqueness(Map<String, Object> body) {
    Object suppliedHandle = body.containsKey("handle") ? body.get("handle") : body.get("username");
    String handle = normalized(suppliedHandle);
    boolean handleTaken = RESERVED_HANDLES.contains(handle)
        || (!handle.isBlank() && creators.handleExists(handle));
    // Keep username_taken during the frontend migration, but never expose email existence.
    return Map.of("handle_taken", handleTaken, "username_taken", handleTaken,
        "available", !handleTaken);
  }

  @Transactional
  public Registration register(RegisterRequest request) {
    if (request == null) {
      throw invalidRegistration();
    }

    String handle = normalized(request.handle());
    String displayName = trimmed(request.displayName());
    String email = normalized(request.email());
    String phone = nullableTrimmed(request.phone());
    String password = request.password();

    if (!HANDLE.matcher(handle).matches()
        || displayName.isBlank() || displayName.length() > 80
        || !EMAIL.matcher(email).matches() || email.length() > 255
        || (phone != null && !PHONE.matcher(phone).matches())
        || password == null || password.length() < 8
        || password.getBytes(StandardCharsets.UTF_8).length > 72) {
      throw invalidRegistration();
    }
    if (RESERVED_HANDLES.contains(handle)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "That handle is reserved for an application page. Choose another handle.");
    }
    long id;
    try {
      id = creators.create(handle, displayName, email, phone, passwordEncoder.encode(password));
    } catch (DataIntegrityViolationException conflict) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Handle or email already exists.");
    }
    stores.createDefault(id, displayName + "'s Store");
    features.createNotificationDefaults(id);
    SessionService.IssuedSession session = sessions.issue(id);
    return new Registration(Map.of("id", id, "handle", handle, "displayName", displayName,
        "plan", "free", "onboarding_next", "/dashboard/", "authenticated", true), session);
  }

  private static ResponseStatusException invalidRegistration() {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST,
        "Use a 3-40 character handle, display name, valid email, optional valid phone, "
            + "and an 8-72 byte password.");
  }

  private static String normalized(Object value) {
    return trimmed(value).toLowerCase(Locale.ROOT);
  }

  private static String trimmed(Object value) {
    return value == null ? "" : String.valueOf(value).trim();
  }

  private static String nullableTrimmed(Object value) {
    String result = trimmed(value);
    return result.isBlank() ? null : result;
  }

  public record Registration(Map<String, Object> account,
                             SessionService.IssuedSession session) {}
}
