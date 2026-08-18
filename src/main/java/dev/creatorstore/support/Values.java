package dev.creatorstore.support;

public final class Values {
  private Values() {}

  public static String text(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  public static long longValue(Object value) {
    return value instanceof Number number ? number.longValue() : Long.parseLong(text(value));
  }

  public static String optional(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }
}
