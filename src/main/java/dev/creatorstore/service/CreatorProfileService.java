package dev.creatorstore.service;

import dev.creatorstore.dto.ProfileUpdateRequest;
import dev.creatorstore.repository.CreatorRepository;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CreatorProfileService {
  public static final String INVALID_HANDLE =
      "Handle must use 3-40 letters, numbers, or underscores.";
  public static final String RESERVED_HANDLE =
      "That handle is reserved for an application page. Choose another handle.";
  public static final String HANDLE_CONFLICT = "Handle already exists.";
  public static final String INVALID_PROFILE =
      "Display name is required and must be at most 80 characters; bio must be at most "
          + "280 characters; phone must be blank or a valid 7-32 character number.";

  private final CreatorRepository creators;

  public CreatorProfileService(CreatorRepository creators) {
    this.creators = creators;
  }

  @Transactional
  public Map<String, Object> update(long creatorId, ProfileUpdateRequest request) {
    if (request == null) throw badRequest(INVALID_PROFILE);

    String handle = CreatorIdentityPolicy.normalizeHandle(request.handle());
    String displayName = CreatorIdentityPolicy.trim(request.displayName());
    String bio = CreatorIdentityPolicy.trim(request.bio());
    String phone = CreatorIdentityPolicy.nullablePhone(request.phone());

    if (!CreatorIdentityPolicy.isValidHandle(handle)) throw badRequest(INVALID_HANDLE);
    if (CreatorIdentityPolicy.isReservedHandle(handle)) throw badRequest(RESERVED_HANDLE);
    if (displayName.isBlank() || displayName.length() > 80 || bio.length() > 280
        || !CreatorIdentityPolicy.isValidPhone(phone)) {
      throw badRequest(INVALID_PROFILE);
    }
    if (creators.handleExistsForOtherCreator(handle, creatorId)) throw conflict();

    try {
      if (creators.updateProfile(creatorId, handle, displayName, bio, phone) == 0) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Creator profile not found.");
      }
    } catch (DataIntegrityViolationException race) {
      throw conflict();
    }
    return creators.findProfile(creatorId);
  }

  private static ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static ResponseStatusException conflict() {
    return new ResponseStatusException(HttpStatus.CONFLICT, HANDLE_CONFLICT);
  }
}
