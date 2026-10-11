package com.creatorstore.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class NormalizerTest {

    @Test
    void emailIsTrimmedAndLowercased() {
        assertEquals("priya@gmail.com", Normalizer.email("  PRIYA@Gmail.com "));
    }

    @Test
    void usernameIsTrimmedAndLowercased() {
        assertEquals("skinglow", Normalizer.username(" SkinGlow "));
    }

    @Test
    void fullNameCollapsesWhitespaceButKeepsCase() {
        assertEquals("Mary Ann", Normalizer.fullName("  Mary   Ann  "));
    }

    @Test
    void phoneStripsFormattingAndKeepsPlus() {
        assertEquals("+919876543210", Normalizer.phone(" +91 (98765)-43210 "));
    }

    @Test
    void blankPhoneBecomesNull() {
        assertNull(Normalizer.phone("   "));
        assertNull(Normalizer.phone(null));
    }
}
