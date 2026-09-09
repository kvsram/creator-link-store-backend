package dev.creatorstore.service;

import dev.creatorstore.dto.RegisterRequest;
import dev.creatorstore.identity.SessionService;
import dev.creatorstore.repository.CreatorRepository;
import dev.creatorstore.repository.FeatureRepository;
import dev.creatorstore.repository.StoreRepository;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AuthenticationService {
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
    String handle = CreatorIdentityPolicy.normalizeHandle(suppliedHandle);
    boolean handleTaken = !CreatorIdentityPolicy.isValidHandle(handle)
        || CreatorIdentityPolicy.isReservedHandle(handle)
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

    String handle = CreatorIdentityPolicy.normalizeHandle(request.handle());
    String displayName = CreatorIdentityPolicy.trim(request.displayName());
    String email = CreatorIdentityPolicy.normalizeEmail(request.email());
    String phone = CreatorIdentityPolicy.nullablePhone(request.phone());
    String password = request.password();

    if (!CreatorIdentityPolicy.isValidHandle(handle)
        || displayName.isBlank() || displayName.length() > 80
        || !CreatorIdentityPolicy.isValidEmail(email)
        || !CreatorIdentityPolicy.isValidPhone(phone)
        || password == null || password.length() < 8
        || password.getBytes(StandardCharsets.UTF_8).length > 72) {
      throw invalidRegistration();
    }
    if (CreatorIdentityPolicy.isReservedHandle(handle)) {
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

  public record Registration(Map<String, Object> account,
                             SessionService.IssuedSession session) {}
}
