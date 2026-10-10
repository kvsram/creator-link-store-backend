package dev.creatorstore.identity;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AuthProductionGuardTest {
  private static final String STABLE_SECRET = "0123456789abcdef0123456789abcdef";

  @Test
  void acceptsACompleteProductionAuthenticationConfiguration() {
    assertThatCode(() -> guard(true, true, "https://creators.example.com",
        "https://creators.example.com", "smtp", "security@example.com", true, false,
        STABLE_SECRET).afterPropertiesSet())
        .doesNotThrowAnyException();
  }

  @Test
  void ignoresProductionOnlyRequirementsInLocalDevelopment() {
    assertThatCode(() -> new AuthProductionGuard("local", false, false,
        "http://localhost:5173", "http://localhost:5173", "test", "", "localhost",
        "", "", false, false, false, 0, 0, 0, false, true, "short")
        .afterPropertiesSet())
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsProductionWithoutSecureCookies() {
    assertRejected(guard(false, true, "https://creators.example.com",
        "https://creators.example.com", "smtp", "security@example.com", true, false,
        STABLE_SECRET), "Secure cookies");
  }

  @Test
  void rejectsDisabledCsrfOrAnyNonHttpsProductionOrigin() {
    assertRejected(guard(true, false, "https://creators.example.com",
        "https://creators.example.com", "smtp", "security@example.com", true, false,
        STABLE_SECRET), "CSRF protection");
    assertRejected(guard(true, true,
        "https://creators.example.com,http://admin.example.com",
        "https://creators.example.com", "smtp", "security@example.com", true, false,
        STABLE_SECRET), "HTTPS origins");
    assertRejected(guard(true, true, "", "https://creators.example.com", "smtp",
        "security@example.com", true, false, STABLE_SECRET), "HTTPS origins");
  }

  @Test
  void rejectsNonHttpsPublicAuthenticationLinks() {
    assertRejected(guard(true, true, "https://creators.example.com",
        "http://creators.example.com", "smtp", "security@example.com", true, false,
        STABLE_SECRET), "HTTPS public URL");
    assertRejected(guard(true, true, "https://creators.example.com",
        "https://configure-before-deploy.invalid", "smtp", "security@example.com", true,
        false, STABLE_SECRET), "HTTPS public URL");
  }

  @Test
  void rejectsPublicAuthenticationOriginThatBrowsersCannotPostFrom() {
    assertRejected(guard(true, true, "https://admin.example.com",
        "https://creators.example.com/verify-email", "smtp", "security@example.com", true,
        false, STABLE_SECRET), "public URL origin in allowed origins");
    assertThatCode(() -> guard(true, true, "https://creators.example.com:443",
        "https://creators.example.com/verify-email", "smtp", "security@example.com", true,
        false, STABLE_SECRET).afterPropertiesSet()).doesNotThrowAnyException();
  }

  @Test
  void rejectsProductionWithoutSmtpOrSender() {
    assertRejected(guard(true, true, "https://creators.example.com",
        "https://creators.example.com", "disabled", "security@example.com", true, false,
        STABLE_SECRET), "SMTP email delivery");
    assertRejected(guard(true, true, "https://creators.example.com",
        "https://creators.example.com", "smtp", "", true, false, STABLE_SECRET),
        "email sender address");
    assertRejected(guard(true, true, "https://creators.example.com",
        "https://creators.example.com", "smtp", "security@example.com", false, false,
        STABLE_SECRET), "email delivery worker");
  }

  @Test
  void rejectsPlaceholderOrTransportUnsafeSmtp() {
    assertRejected(guardWithSmtp("smtp.provider.example.invalid", true, true, true),
        "configured SMTP host");
    assertRejected(guardWithSmtp("email-smtp.us-east-2.amazonaws.com", false, true, true),
        "authenticated SMTP");
    assertRejected(guardWithSmtp("email-smtp.us-east-2.amazonaws.com", true, true, false),
        "mandatory SMTP STARTTLS");
  }

  @Test
  void rejectsMissingSmtpCredentialsOrTimeoutsOutsideTheLease() {
    assertRejected(guardWithSmtp("email-smtp.us-east-2.amazonaws.com", "", "smtp-password",
        true, true, true, 10_000, 10_000, 10_000), "SMTP credentials");
    assertRejected(guardWithSmtp("email-smtp.us-east-2.amazonaws.com", "smtp-user",
        "smtp-password", true, true, true, 0, 10_000, 10_000), "SMTP timeouts");
    assertRejected(guardWithSmtp("email-smtp.us-east-2.amazonaws.com", "smtp-user",
        "smtp-password", true, true, true, AuthDeliveryRepository.LEASE.toMillis(), 10_000,
        10_000), "SMTP timeouts");
  }

  @Test
  void rejectsTestMailboxOrEphemeralFingerprintSecretInProduction() {
    assertRejected(guard(true, true, "https://creators.example.com",
        "https://creators.example.com", "smtp", "security@example.com", true, true,
        STABLE_SECRET), "test authentication mailbox");
    assertRejected(guard(true, true, "https://creators.example.com",
        "https://creators.example.com", "smtp", "security@example.com", true, false,
        "too-short"), "stable fingerprint secret");
  }

  private static AuthProductionGuard guard(boolean secureCookies, boolean csrfEnabled,
                                           String origins, String publicBaseUrl,
                                           String emailMode, String emailFrom,
                                           boolean deliveryWorkerEnabled,
                                           boolean testDeliveryEnabled,
                                           String fingerprintSecret) {
    return new AuthProductionGuard("production", secureCookies, csrfEnabled, origins,
        publicBaseUrl, emailMode, emailFrom, "email-smtp.us-east-2.amazonaws.com", "smtp-user",
        "smtp-password", true, true, true, 10_000, 10_000, 10_000, deliveryWorkerEnabled,
        testDeliveryEnabled, fingerprintSecret);
  }

  private static AuthProductionGuard guardWithSmtp(String host, boolean auth,
                                                    boolean startTlsEnabled,
                                                    boolean startTlsRequired) {
    return guardWithSmtp(host, "smtp-user", "smtp-password", auth, startTlsEnabled,
        startTlsRequired, 10_000, 10_000, 10_000);
  }

  private static AuthProductionGuard guardWithSmtp(String host, String username, String password,
                                                    boolean auth, boolean startTlsEnabled,
                                                    boolean startTlsRequired,
                                                    long connectionTimeoutMillis,
                                                    long readTimeoutMillis,
                                                    long writeTimeoutMillis) {
    return new AuthProductionGuard("production", true, true,
        "https://creators.example.com", "https://creators.example.com", "smtp",
        "security@example.com", host, username, password, auth, startTlsEnabled,
        startTlsRequired, connectionTimeoutMillis, readTimeoutMillis, writeTimeoutMillis, true,
        false, STABLE_SECRET);
  }

  private static void assertRejected(AuthProductionGuard guard, String messageFragment) {
    assertThatThrownBy(guard::afterPropertiesSet)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(messageFragment);
  }
}
