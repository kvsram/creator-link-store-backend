package com.creatorstore.auth;

import com.creatorstore.auth.AuthDtos.AvailabilityResponse;
import com.creatorstore.auth.AuthDtos.LoginRequest;
import com.creatorstore.auth.AuthDtos.LogoutAllResponse;
import com.creatorstore.auth.AuthDtos.LogoutRequest;
import com.creatorstore.auth.AuthDtos.RefreshRequest;
import com.creatorstore.auth.AuthDtos.SignupRequest;
import com.creatorstore.auth.AuthDtos.SignupResponse;
import com.creatorstore.auth.AuthDtos.TokenResponse;
import com.creatorstore.security.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Creator authentication endpoints.
 *
 *  GET  /api/auth/username-availability?username=x   public
 *  POST /api/auth/signup                              public
 *  POST /api/auth/login                               public
 *  POST /api/auth/refresh                             public (the refresh token is the credential)
 *  POST /api/auth/logout                              public (revokes the given refresh token)
 *  POST /api/auth/logout-all                          ROLE_CREATOR
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @GetMapping("/username-availability")
    public AvailabilityResponse usernameAvailability(@RequestParam String username) {
        return new AvailabilityResponse(authService.isUsernameAvailable(username));
    }

    @PostMapping("/signup")
    public ResponseEntity<SignupResponse> signup(@Valid @RequestBody SignupRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.signup(request));
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        return authService.login(request, ClientInfo.from(http));
    }

    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest request, HttpServletRequest http) {
        return authService.refresh(request.refreshToken(), ClientInfo.from(http));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody LogoutRequest request) {
        authService.logout(request.refreshToken());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/logout-all")
    public LogoutAllResponse logoutAll(@AuthenticationPrincipal Jwt jwt) {
        AuthenticatedUser user = AuthenticatedUser.from(jwt);
        return new LogoutAllResponse(authService.logoutAll(user.id()));
    }
}
