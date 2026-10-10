package dev.creatorstore.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Creates keyed, non-plaintext correlation values for security events. */
@Service
public class SecurityFingerprintService {
  private final byte[] key;

  public SecurityFingerprintService(
      @Value("${app.auth.fingerprint-secret:}") String configuredSecret) {
    String secret = configuredSecret == null || configuredSecret.isBlank()
        ? UUID.randomUUID().toString() : configuredSecret;
    this.key = secret.getBytes(StandardCharsets.UTF_8);
  }

  public String fingerprint(String value) {
    if (value == null || value.isBlank()) return null;
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception exception) {
      throw new IllegalStateException("Unable to fingerprint security data", exception);
    }
  }

  public boolean constantTimeEquals(String expected, String supplied) {
    if (expected == null || supplied == null) return false;
    return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
        supplied.getBytes(StandardCharsets.UTF_8));
  }
}
