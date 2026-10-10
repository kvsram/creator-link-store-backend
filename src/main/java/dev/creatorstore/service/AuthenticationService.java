package dev.creatorstore.service;

import dev.creatorstore.dto.RegisterRequest;
import dev.creatorstore.identity.ClientContext;
import dev.creatorstore.identity.SessionService;
import dev.creatorstore.repository.CreatorRepository;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class AuthenticationService {
  private final CreatorRepository creators;
  private final IdentityAuthenticationService identityAuthentication;

  public AuthenticationService(CreatorRepository creators,
                               IdentityAuthenticationService identityAuthentication) {
    this.creators = creators;
    this.identityAuthentication = identityAuthentication;
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

  public Registration register(RegisterRequest request) {
    return register(request, ClientContext.unknown());
  }

  public Registration register(RegisterRequest request, ClientContext context) {
    IdentityAuthenticationService.Registration result =
        identityAuthentication.register(request, context, false);
    return new Registration(result.account(), result.session());
  }

  public record Registration(Map<String, Object> account,
                             SessionService.IssuedSession session) {}
}
