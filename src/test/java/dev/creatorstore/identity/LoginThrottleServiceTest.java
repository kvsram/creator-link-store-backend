package dev.creatorstore.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class LoginThrottleServiceTest {
  private static final Duration WINDOW = Duration.ofMinutes(5);

  @Test
  void scopesAreIndependentAndChallengeLimitsApplyToIpAndNormalizedIdentifier() {
    LoginThrottleService throttle = new LoginThrottleService(
        new LoginThrottleService.Policy(2, WINDOW),
        new LoginThrottleService.Policy(1, WINDOW),
        new LoginThrottleService.Policy(10, WINDOW),
        new LoginThrottleService.Policy(1, WINDOW),
        Clock.fixed(Instant.parse("2026-10-09T12:00:00Z"), ZoneOffset.UTC));

    assertThat(throttle.allowLogin("203.0.113.10")).isTrue();
    assertThat(throttle.allowLogin("203.0.113.10")).isTrue();
    assertThat(throttle.allowLogin("203.0.113.10")).isFalse();
    assertThat(throttle.allowRegistration("203.0.113.10")).isTrue();
    assertThat(throttle.allowRegistration("203.0.113.10")).isFalse();

    assertThat(throttle.allowChallenge("203.0.113.11", " Creator@Example.com ")).isTrue();
    assertThat(throttle.allowChallenge("203.0.113.12", "creator@example.com")).isFalse();
  }
}
