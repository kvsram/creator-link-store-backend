package dev.creatorstore.identity;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/** Deny-by-default API authorization and cookie-session integration for Spring Security. */
@Configuration
public class SecurityConfiguration {
  @Bean
  SecurityFilterChain apiSecurity(HttpSecurity http, SessionAuthenticationFilter sessions,
                                  AuthResponseHeadersFilter authHeaders,
      @Value("${app.security.csrf-enabled:true}") boolean csrfEnabled,
      @Value("${app.secure-cookies:false}") boolean secureCookies) throws Exception {
    http
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .requestCache(cache -> cache.disable())
        .httpBasic(basic -> basic.disable())
        .formLogin(form -> form.disable())
        .logout(logout -> logout.disable())
        .cors(Customizer.withDefaults())
        .exceptionHandling(errors -> errors
            .authenticationEntryPoint((request, response, failure) ->
                json(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "AUTHENTICATION_REQUIRED", "Authentication required."))
            .accessDeniedHandler((request, response, failure) -> json(response,
                HttpServletResponse.SC_FORBIDDEN,
                failure instanceof CsrfException ? "CSRF_TOKEN_INVALID" : "ACCESS_DENIED",
                failure instanceof CsrfException ? "Security token is missing or invalid."
                    : "Access denied.")))
        .authorizeHttpRequests(authorize -> authorize
            .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
            .requestMatchers("/health", "/error", "/api/public/**", "/api/events/**",
                "/events").permitAll()
            .requestMatchers(HttpMethod.GET, "/api/v1/auth/csrf",
                "/api/v1/auth/test-deliveries/**", "/api/v1/payments/config",
                "/api/v1/integrations/instagram/config", "/api/v1/webhooks/instagram")
                .permitAll()
            .requestMatchers(HttpMethod.POST, "/api/v1/auth/registrations",
                "/api/v1/auth/email-verifications",
                "/api/v1/auth/email-verifications/resend", "/api/v1/auth/sessions",
                "/api/v1/auth/password-reset-requests", "/api/v1/auth/password-resets",
                "/api/v1/authentication/check-unique-taken", "/api/auth/register",
                "/api/auth/login", "/api/v1/checkout/**", "/api/v1/payments/razorpay/verify",
                "/api/v1/webhooks/**").permitAll()
            .requestMatchers("/api/auth/**", "/api/v1/**").hasRole("CREATOR")
            .anyRequest().denyAll())
        .addFilterBefore(sessions, AnonymousAuthenticationFilter.class)
        .addFilterAfter(authHeaders, SessionAuthenticationFilter.class);

    if (csrfEnabled) {
      CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
      repository.setCookiePath("/");
      repository.setCookieCustomizer(cookie -> cookie.secure(secureCookies).sameSite("Lax"));
      http.csrf(csrf -> csrf.csrfTokenRepository(repository)
          .ignoringRequestMatchers("/api/v1/webhooks/**", "/api/public/**", "/api/events/**",
              "/events", "/api/v1/checkout/**", "/api/v1/payments/razorpay/verify"));
    } else {
      http.csrf(csrf -> csrf.disable());
    }
    return http.build();
  }

  @Bean
  CorsConfigurationSource corsConfigurationSource(
      @Value("${app.allowed-origins:http://localhost:5173,http://localhost:3000}") String origins) {
    CorsConfiguration configuration = new CorsConfiguration();
    configuration.setAllowedOrigins(Arrays.stream(origins.split(","))
        .map(String::trim).filter(value -> !value.isBlank()).toList());
    configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
    configuration.setAllowedHeaders(List.of("Content-Type", "Idempotency-Key", "X-XSRF-TOKEN",
        "X-Test-Auth-Key", "X-Request-Id"));
    configuration.setAllowCredentials(true);
    configuration.setMaxAge(3600L);
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/api/**", configuration);
    return source;
  }

  private static void json(HttpServletResponse response, int status, String code, String message)
      throws IOException {
    response.setStatus(status);
    response.setContentType("application/json");
    response.getWriter().write("{\"error\":\"" + message + "\",\"code\":\"" + code + "\"}");
  }
}
