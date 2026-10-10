package dev.creatorstore.identity;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

/** Owns password-verifier evolution while legacy BCrypt accounts are migrated on login. */
@Service
public class PasswordService {
  private final Argon2PasswordEncoder argon2 =
      new Argon2PasswordEncoder(16, 32, 1, 19_456, 2);
  private final BCryptPasswordEncoder bcrypt;
  private final String dummyVerifier;

  public PasswordService(BCryptPasswordEncoder bcrypt) {
    this.bcrypt = bcrypt;
    this.dummyVerifier = argon2.encode(UUID.randomUUID().toString());
  }

  public String encode(String rawPassword) {
    return argon2.encode(rawPassword);
  }

  public String legacyVerifier(String rawPassword) {
    if (rawPassword.getBytes(StandardCharsets.UTF_8).length <= 72) {
      return bcrypt.encode(rawPassword);
    }
    // The normalized Argon2 credential is authoritative. Keep the deprecated column unusable
    // instead of truncating a long password or silently changing its meaning.
    return bcrypt.encode(UUID.randomUUID().toString());
  }

  /** A BCrypt value that cannot authenticate the caller's requested password on an old image. */
  public String disabledLegacyVerifier() {
    return bcrypt.encode(UUID.randomUUID().toString());
  }

  public boolean matches(String rawPassword, String verifier) {
    if (verifier == null) return false;
    if (verifier.startsWith("$argon2")) return argon2.matches(rawPassword, verifier);
    if (verifier.startsWith("$2a$") || verifier.startsWith("$2b$")
        || verifier.startsWith("$2y$")) {
      return bcrypt.matches(rawPassword, verifier);
    }
    return false;
  }

  public boolean needsUpgrade(String verifier) {
    return verifier == null || !verifier.startsWith("$argon2")
        || argon2.upgradeEncoding(verifier);
  }

  public boolean isLegacyBcrypt(String verifier) {
    return verifier != null && (verifier.startsWith("$2a$") || verifier.startsWith("$2b$")
        || verifier.startsWith("$2y$"));
  }

  public void consumeComparableWork(String rawPassword) {
    argon2.matches(rawPassword, dummyVerifier);
  }
}
