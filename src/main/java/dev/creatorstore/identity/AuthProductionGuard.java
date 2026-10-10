package dev.creatorstore.identity;

import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Refuses to start a production process with test or transport-unsafe auth settings. */
@Component
public class AuthProductionGuard implements InitializingBean {
  private final String environment;
  private final boolean secureCookies;
  private final boolean csrfEnabled;
  private final String allowedOrigins;
  private final String publicBaseUrl;
  private final String emailMode;
  private final String emailFrom;
  private final String smtpHost;
  private final String smtpUsername;
  private final String smtpPassword;
  private final boolean smtpAuth;
  private final boolean smtpStartTlsEnabled;
  private final boolean smtpStartTlsRequired;
  private final long smtpConnectionTimeoutMillis;
  private final long smtpReadTimeoutMillis;
  private final long smtpWriteTimeoutMillis;
  private final boolean deliveryWorkerEnabled;
  private final boolean testDeliveryEnabled;
  private final String fingerprintSecret;

  public AuthProductionGuard(@Value("${app.environment:local}") String environment,
      @Value("${app.secure-cookies:false}") boolean secureCookies,
      @Value("${app.security.csrf-enabled:true}") boolean csrfEnabled,
      @Value("${app.allowed-origins:}") String allowedOrigins,
      @Value("${app.auth.public-base-url:http://localhost:5173}") String publicBaseUrl,
      @Value("${app.auth.email-mode:disabled}") String emailMode,
      @Value("${app.auth.email-from:}") String emailFrom,
      @Value("${spring.mail.host:localhost}") String smtpHost,
      @Value("${spring.mail.username:}") String smtpUsername,
      @Value("${spring.mail.password:}") String smtpPassword,
      @Value("${spring.mail.properties.mail.smtp.auth:false}") boolean smtpAuth,
      @Value("${spring.mail.properties.mail.smtp.starttls.enable:false}")
      boolean smtpStartTlsEnabled,
      @Value("${spring.mail.properties.mail.smtp.starttls.required:false}")
      boolean smtpStartTlsRequired,
      @Value("${spring.mail.properties.mail.smtp.connectiontimeout:0}")
      long smtpConnectionTimeoutMillis,
      @Value("${spring.mail.properties.mail.smtp.timeout:0}") long smtpReadTimeoutMillis,
      @Value("${spring.mail.properties.mail.smtp.writetimeout:0}") long smtpWriteTimeoutMillis,
      @Value("${app.auth.delivery-worker-enabled:true}") boolean deliveryWorkerEnabled,
      @Value("${app.auth.test-delivery-enabled:false}") boolean testDeliveryEnabled,
      @Value("${app.auth.fingerprint-secret:}") String fingerprintSecret) {
    this.environment = environment;
    this.secureCookies = secureCookies;
    this.csrfEnabled = csrfEnabled;
    this.allowedOrigins = allowedOrigins;
    this.publicBaseUrl = publicBaseUrl;
    this.emailMode = emailMode;
    this.emailFrom = emailFrom;
    this.smtpHost = smtpHost;
    this.smtpUsername = smtpUsername;
    this.smtpPassword = smtpPassword;
    this.smtpAuth = smtpAuth;
    this.smtpStartTlsEnabled = smtpStartTlsEnabled;
    this.smtpStartTlsRequired = smtpStartTlsRequired;
    this.smtpConnectionTimeoutMillis = smtpConnectionTimeoutMillis;
    this.smtpReadTimeoutMillis = smtpReadTimeoutMillis;
    this.smtpWriteTimeoutMillis = smtpWriteTimeoutMillis;
    this.deliveryWorkerEnabled = deliveryWorkerEnabled;
    this.testDeliveryEnabled = testDeliveryEnabled;
    this.fingerprintSecret = fingerprintSecret;
  }

  @Override
  public void afterPropertiesSet() {
    if (!"production".equalsIgnoreCase(environment)) return;
    require(secureCookies, "Production authentication requires Secure cookies");
    require(csrfEnabled, "Production authentication requires CSRF protection");
    var origins = Arrays.stream(allowedOrigins.split(",")).map(String::trim)
        .filter(value -> !value.isBlank()).toList();
    require(!origins.isEmpty() && origins.stream().allMatch(AuthProductionGuard::isHttpsOrigin),
        "Production authentication requires HTTPS origins");
    require(isHttpsPublicBase(publicBaseUrl),
        "Production authentication requires an HTTPS public URL");
    String publicOrigin = normalizedOrigin(publicBaseUrl);
    require(publicOrigin != null && origins.stream()
            .map(AuthProductionGuard::normalizedOrigin).anyMatch(publicOrigin::equals),
        "Production authentication requires the public URL origin in allowed origins");
    require("smtp".equalsIgnoreCase(emailMode),
        "Production authentication requires SMTP email delivery");
    require(emailFrom != null && emailFrom.contains("@")
            && !emailFrom.toLowerCase().endsWith(".invalid"),
        "Production authentication requires an email sender address");
    require(smtpHost != null && !smtpHost.isBlank()
            && !"localhost".equalsIgnoreCase(smtpHost.trim())
            && !smtpHost.toLowerCase().endsWith(".invalid"),
        "Production authentication requires a configured SMTP host");
    require(smtpAuth, "Production authentication requires authenticated SMTP");
    require(notBlank(smtpUsername) && notBlank(smtpPassword),
        "Production authentication requires SMTP credentials");
    require(smtpStartTlsEnabled && smtpStartTlsRequired,
        "Production authentication requires mandatory SMTP STARTTLS");
    long leaseMillis = AuthDeliveryRepository.LEASE.toMillis();
    require(validTimeout(smtpConnectionTimeoutMillis, leaseMillis)
            && validTimeout(smtpReadTimeoutMillis, leaseMillis)
            && validTimeout(smtpWriteTimeoutMillis, leaseMillis),
        "Production authentication requires positive SMTP timeouts shorter than the delivery lease");
    require(deliveryWorkerEnabled,
        "Production authentication requires the email delivery worker");
    require(!testDeliveryEnabled,
        "The test authentication mailbox must be disabled in production");
    require(fingerprintSecret != null && fingerprintSecret.length() >= 32,
        "Production authentication requires a stable fingerprint secret of at least 32 characters");
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new IllegalStateException(message);
  }

  private static boolean notBlank(String value) {
    return value != null && !value.isBlank();
  }

  private static boolean validTimeout(long timeoutMillis, long leaseMillis) {
    return timeoutMillis > 0 && timeoutMillis < leaseMillis;
  }

  private static String normalizedOrigin(String value) {
    try {
      if (value == null || value.isBlank()) return null;
      URI uri = URI.create(value.trim());
      if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) return null;
      String host = uri.getHost().toLowerCase(Locale.ROOT);
      if (host.contains(":") && !host.startsWith("[")) host = "[" + host + "]";
      int port = uri.getPort();
      return "https://" + host + (port == -1 || port == 443 ? "" : ":" + port);
    } catch (IllegalArgumentException invalid) {
      return null;
    }
  }

  private static boolean isHttpsOrigin(String value) {
    try {
      URI uri = URI.create(value);
      return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
          && uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null
          && (uri.getPath() == null || uri.getPath().isEmpty())
          && !uri.getHost().toLowerCase().endsWith(".invalid")
          && !"localhost".equalsIgnoreCase(uri.getHost());
    } catch (IllegalArgumentException invalid) {
      return false;
    }
  }

  private static boolean isHttpsPublicBase(String value) {
    try {
      if (value == null || value.isBlank()) return false;
      URI uri = URI.create(value.trim());
      return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
          && uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null
          && !uri.getHost().toLowerCase().endsWith(".invalid")
          && !"localhost".equalsIgnoreCase(uri.getHost());
    } catch (IllegalArgumentException invalid) {
      return false;
    }
  }
}
