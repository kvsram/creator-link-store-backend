package dev.creatorstore.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
    @Size(max = 40) String handle,
    @NotBlank @Size(max = 80) String displayName,
    @NotBlank @Email @Size(max = 320) String email,
    @Size(max = 32) String phone,
    @NotBlank @Size(min = 12, max = 128) String password) {
  public RegisterRequest {
    // Bean Validation runs before the service's normalization. Trim ordinary form fields here so
    // harmless browser whitespace does not turn a valid address into a validation failure. Never
    // trim passwords because whitespace may intentionally be part of a passphrase.
    handle = trim(handle);
    displayName = trim(displayName);
    email = trim(email);
    phone = trim(phone);
  }

  private static String trim(String value) {
    return value == null ? null : value.trim();
  }
}
