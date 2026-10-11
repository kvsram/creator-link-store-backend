package com.creatorstore.config;

import com.creatorstore.common.ErrorResponse;
import com.creatorstore.security.Role;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Stateless API security.
 *
 * Public endpoints are listed ONCE in {@link #PUBLIC_POST_PATHS} / {@link #PUBLIC_GET_PATHS}.
 * Everything else under /api needs a valid Bearer token:
 *   /api/admin/**  -> ROLE_ADMIN  (JWT claim role=ADMIN; admin_role adds ROLE_SUPER_ADMIN | ROLE_SUPPORT | ROLE_FINANCE)
 *   /api/**        -> ROLE_CREATOR
 * Anything outside /api (except /error) is denied.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    static final Set<String> PUBLIC_POST_PATHS = Set.of(
            "/api/auth/signup",
            "/api/auth/login",
            "/api/auth/refresh",
            "/api/auth/logout",
            "/api/verification/phone/confirm",
            "/api/admin/auth/login");

    static final Set<String> PUBLIC_GET_PATHS = Set.of(
            "/api/auth/username-availability");

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtAuthenticationConverter jwtAuthenticationConverter,
                                                   BearerTokenResolver bearerTokenResolver,
                                                   ObjectMapper objectMapper) throws Exception {
        AuthenticationEntryPoint unauthorized = (request, response, ex) -> writeError(response, objectMapper,
                HttpServletResponse.SC_UNAUTHORIZED, new ErrorResponse("UNAUTHORIZED", "A valid access token is required."));
        AccessDeniedHandler forbidden = (request, response, ex) -> writeError(response, objectMapper,
                HttpServletResponse.SC_FORBIDDEN, new ErrorResponse("FORBIDDEN", "You do not have permission to do this."));

        http
            .csrf(AbstractHttpConfigurer::disable)                     // bearer tokens, no cookies: CSRF does not apply
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                .requestMatchers("/error").permitAll()
                .requestMatchers(HttpMethod.GET,  PUBLIC_GET_PATHS.toArray(String[]::new)).permitAll()
                .requestMatchers(HttpMethod.POST, PUBLIC_POST_PATHS.toArray(String[]::new)).permitAll()
                .requestMatchers("/api/admin/**").hasRole(Role.ADMIN.name())
                .requestMatchers("/api/**").hasRole(Role.CREATOR.name())
                .anyRequest().denyAll()
            )
            .oauth2ResourceServer(o -> o
                .bearerTokenResolver(bearerTokenResolver)
                .jwt(j -> j.jwtAuthenticationConverter(jwtAuthenticationConverter))
                .authenticationEntryPoint(unauthorized)
                .accessDeniedHandler(forbidden)
            )
            .exceptionHandling(e -> e
                .authenticationEntryPoint(unauthorized)
                .accessDeniedHandler(forbidden)
            );
        return http.build();
    }

    /**
     * Public endpoints ignore the Authorization header. Without this, a browser that still sends an expired
     * access token to /api/auth/refresh would get 401 from the token filter before the refresh ever ran.
     */
    @Bean
    public BearerTokenResolver bearerTokenResolver() {
        DefaultBearerTokenResolver delegate = new DefaultBearerTokenResolver();
        return (HttpServletRequest request) -> isPublic(request) ? null : delegate.resolve(request);
    }

    /** Maps JWT claims to Spring authorities. The "sub" claim stays the principal name (creator or admin id). */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter((Jwt jwt) -> {
            List<GrantedAuthority> authorities = new ArrayList<>(2);
            String role = jwt.getClaimAsString("role");
            if (role != null) {
                authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
            }
            String adminRole = jwt.getClaimAsString("admin_role");
            if (adminRole != null) {
                authorities.add(new SimpleGrantedAuthority("ROLE_" + adminRole));
            }
            return authorities;
        });
        return converter;
    }

    private static boolean isPublic(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        return switch (request.getMethod()) {
            case "POST" -> PUBLIC_POST_PATHS.contains(path);
            case "GET"  -> PUBLIC_GET_PATHS.contains(path);
            default     -> false;
        };
    }

    private static void writeError(HttpServletResponse response, ObjectMapper objectMapper,
                                   int status, ErrorResponse body) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
