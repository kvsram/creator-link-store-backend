package dev.creatorstore.identity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Low-scale per-instance IP guard; replace with Redis before adding backend replicas. */
@Service
public class LoginThrottleService {
  private static final int MAXIMUM_TRACKED_KEYS = 10_000;
  private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
  private final Policy loginPolicy;
  private final Policy registrationPolicy;
  private final Policy challengeIpPolicy;
  private final Policy challengeIdentifierPolicy;
  private final Clock clock;

  @Autowired
  public LoginThrottleService(
      @Value("${app.auth.login-ip-attempts:30}") int maximumAttempts,
      @Value("${app.auth.login-ip-window:PT5M}") Duration duration,
      @Value("${app.auth.registration-ip-attempts:10}") int registrationAttempts,
      @Value("${app.auth.registration-ip-window:PT1H}") Duration registrationWindow,
      @Value("${app.auth.challenge-ip-attempts:20}") int challengeIpAttempts,
      @Value("${app.auth.challenge-identifier-attempts:5}") int challengeIdentifierAttempts,
      @Value("${app.auth.challenge-window:PT15M}") Duration challengeWindow) {
    this(new Policy(maximumAttempts, duration),
        new Policy(registrationAttempts, registrationWindow),
        new Policy(challengeIpAttempts, challengeWindow),
        new Policy(challengeIdentifierAttempts, challengeWindow), Clock.systemUTC());
  }

  LoginThrottleService(Policy loginPolicy, Policy registrationPolicy,
                       Policy challengeIpPolicy, Policy challengeIdentifierPolicy, Clock clock) {
    this.loginPolicy = loginPolicy;
    this.registrationPolicy = registrationPolicy;
    this.challengeIpPolicy = challengeIpPolicy;
    this.challengeIdentifierPolicy = challengeIdentifierPolicy;
    this.clock = clock;
  }

  /** Compatibility alias retained for callers compiled against the first throttle. */
  public boolean allow(String address) {
    return allowLogin(address);
  }

  public boolean allowLogin(String address) {
    return allow("login-ip", normalized(address), loginPolicy);
  }

  public boolean allowRegistration(String address) {
    return allow("registration-ip", normalized(address), registrationPolicy);
  }

  public boolean allowChallenge(String address, String normalizedIdentifier) {
    // Evaluate both so a blocked IP cannot avoid advancing the address-specific window.
    boolean ipAllowed = allow("challenge-ip", normalized(address), challengeIpPolicy);
    boolean identifierAllowed = allow("challenge-identifier", normalized(normalizedIdentifier),
        challengeIdentifierPolicy);
    return ipAllowed && identifierAllowed;
  }

  private boolean allow(String scope, String value, Policy policy) {
    String key = scope + ':' + SessionCookies.hash(value);
    Instant now = clock.instant();
    if (windows.size() >= MAXIMUM_TRACKED_KEYS && !windows.containsKey(key)) {
      windows.entrySet().removeIf(entry -> !now.isBefore(
          entry.getValue().startedAt.plus(entry.getValue().duration)));
      if (windows.size() >= MAXIMUM_TRACKED_KEYS) return false;
    }
    Window current = windows.compute(key, (ignored, prior) -> {
      if (prior == null || !now.isBefore(prior.startedAt.plus(prior.duration))) {
        return new Window(now, 1, policy.duration);
      }
      return new Window(prior.startedAt, prior.count + 1, prior.duration);
    });
    return current.count <= policy.maximumAttempts;
  }

  private static String normalized(String value) {
    return value == null || value.isBlank() ? "unknown" : value.trim().toLowerCase();
  }

  record Policy(int maximumAttempts, Duration duration) {
    Policy {
      if (maximumAttempts < 1 || duration.isZero() || duration.isNegative()) {
        throw new IllegalArgumentException("Throttle policy must be positive");
      }
    }
  }

  private record Window(Instant startedAt, int count, Duration duration) {}
}
