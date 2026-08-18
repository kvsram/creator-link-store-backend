package dev.creatorstore.service;

import dev.creatorstore.dto.RegisterRequest;
import dev.creatorstore.repository.CreatorRepository;
import dev.creatorstore.repository.FeatureRepository;
import dev.creatorstore.repository.StoreRepository;
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

  public AuthenticationService(CreatorRepository creators, StoreRepository stores,
                               FeatureRepository features, BCryptPasswordEncoder passwordEncoder) {
    this.creators = creators;
    this.stores = stores;
    this.features = features;
    this.passwordEncoder = passwordEncoder;
  }

  public Map<String, Object> uniqueness(Map<String, Object> body) {
    String handle = String.valueOf(body.getOrDefault("username", "")).toLowerCase();
    String email = String.valueOf(body.getOrDefault("email", "")).toLowerCase();
    boolean usernameTaken = !handle.isBlank() && creators.handleExists(handle);
    boolean emailTaken = !email.isBlank() && creators.emailExists(email);
    return Map.of("username_taken", usernameTaken, "email_taken", emailTaken,
        "available", !usernameTaken && !emailTaken);
  }

  @Transactional
  public Map<String, Object> register(RegisterRequest request) {
    if (request.handle() == null || !request.handle().matches("[a-zA-Z0-9_]{3,40}")
        || request.email() == null || !request.email().contains("@")
        || request.password() == null || request.password().length() < 8) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "Use a 3-40 character handle, valid email, and 8+ character password.");
    }
    String handle = request.handle().toLowerCase();
    String email = request.email().toLowerCase();
    try {
      long id = creators.create(handle, request.displayName(), email, request.phone(),
          passwordEncoder.encode(request.password()));
      stores.createDefault(id, request.displayName() + "'s Store");
      features.createNotificationDefaults(id);
      return Map.of("id", id, "handle", handle, "onboarding_next", "/subscribe/socials");
    } catch (DataIntegrityViolationException conflict) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Handle or email already exists.");
    }
  }
}
