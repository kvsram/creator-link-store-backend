package dev.creatorstore.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class SessionCookiesTest {
  @Test
  void tokensAreRandomAndStoredAsFixedLengthHashes() {
    String first = SessionCookies.newToken();
    String second = SessionCookies.newToken();
    assertNotEquals(first, second);
    assertNotEquals(first, SessionCookies.hash(first));
    assertEquals(64, SessionCookies.hash(first).length());
  }
}
