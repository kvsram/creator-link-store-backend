package dev.creatorstore.service;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Shared normalization and validation rules for creator identity fields. */
public final class CreatorIdentityPolicy {
  private static final Pattern HANDLE = Pattern.compile("[a-zA-Z0-9_]{3,40}");
  private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
  private static final Pattern PHONE = Pattern.compile("^[+()0-9 .-]{7,32}$");
  private static final Set<String> RESERVED_HANDLES = Set.of(
      "about", "admin", "api", "assets", "dashboard", "features", "health", "help",
      "login", "logout", "pricing", "privacy", "register", "settings", "signup",
      "static", "support", "terms", "www");

  private CreatorIdentityPolicy() {}

  public static String normalizeHandle(Object value) {
    return normalize(value);
  }

  public static String normalizeEmail(Object value) {
    return normalize(value);
  }

  public static String trim(Object value) {
    return value == null ? "" : String.valueOf(value).trim();
  }

  public static String nullablePhone(Object value) {
    String result = trim(value);
    return result.isBlank() ? null : result;
  }

  public static boolean isValidHandle(String handle) {
    return HANDLE.matcher(handle).matches();
  }

  public static boolean isReservedHandle(String handle) {
    return RESERVED_HANDLES.contains(handle);
  }

  public static boolean isValidEmail(String email) {
    return email.length() <= 255 && EMAIL.matcher(email).matches();
  }

  public static boolean isValidPhone(String phone) {
    return phone == null || PHONE.matcher(phone).matches();
  }

  private static String normalize(Object value) {
    return trim(value).toLowerCase(Locale.ROOT);
  }
}
