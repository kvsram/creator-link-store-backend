package com.creatorstore.common;

import java.util.Locale;

/**
 * Cleans user input ONCE, in the service layer, before it reaches the database.
 * The database stores only normalized values (and has CHECK constraints that say so).
 */
public final class Normalizer {

    private Normalizer() {}

    /** "  Priya@Gmail.com " becomes "priya@gmail.com". Nothing else is changed. */
    public static String email(String raw) {
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    /** "SkinGlow" becomes "skinglow". */
    public static String username(String raw) {
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    /** "  Mary   Ann  " becomes "Mary Ann". Case is preserved. */
    public static String fullName(String raw) {
        return raw.trim().replaceAll("\\s+", " ");
    }

    /** Removes spaces, dashes and brackets. Returns null for blank input. "+91 98765-43210" becomes "+919876543210". */
    public static String phone(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim().replaceAll("[\\s\\-()]", "");
    }
}
