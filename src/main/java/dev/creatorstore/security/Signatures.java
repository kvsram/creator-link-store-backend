package dev.creatorstore.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class Signatures {
  private Signatures() {}

  public static boolean verifyHexHmac(String secret, String message, String provided) {
    return verifyHexHmac(secret, message.getBytes(StandardCharsets.UTF_8), provided);
  }

  public static boolean verifyHexHmac(String secret, byte[] message, String provided) {
    if (secret == null || secret.isBlank() || provided == null || !provided.matches("[0-9a-fA-F]{64}")) return false;
    return MessageDigest.isEqual(
        hmac(secret, message).getBytes(StandardCharsets.US_ASCII),
        provided.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
  }

  public static boolean verifyStripe(String secret, byte[] payload, String header, long nowSeconds) {
    if (header == null) return false;
    String timestamp = null;
    List<String> signatures = new ArrayList<>();
    for (String part : header.split(",")) {
      String[] pair = part.trim().split("=", 2);
      if (pair.length == 2 && pair[0].equals("t")) timestamp = pair[1];
      if (pair.length == 2 && pair[0].equals("v1")) signatures.add(pair[1]);
    }
    if (timestamp == null) return false;
    try {
      long sent = Long.parseLong(timestamp);
      if (Math.abs(nowSeconds - sent) > 300) return false;
      byte[] signed = (timestamp + "." + new String(payload, StandardCharsets.UTF_8))
          .getBytes(StandardCharsets.UTF_8);
      String expected = hmac(secret, signed);
      return signatures.stream().anyMatch(value -> value.matches("[0-9a-fA-F]{64}")
          && MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
              value.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII)));
    } catch (NumberFormatException ignored) {
      return false;
    }
  }

  public static String sha256(byte[] payload) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }

  public static String hmac(String secret, byte[] message) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(message));
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }
}
