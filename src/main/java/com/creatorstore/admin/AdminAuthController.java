package com.creatorstore.admin;

import com.creatorstore.security.AdminRole;
import com.creatorstore.security.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 *  POST /api/admin/auth/login   public
 *  GET  /api/admin/me           ROLE_ADMIN
 *
 * Every other /api/admin/** endpoint added later is protected by the path rule in SecurityConfig.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminAuthController {

    private final AdminAuthService adminAuthService;

    public AdminAuthController(AdminAuthService adminAuthService) {
        this.adminAuthService = adminAuthService;
    }

    @PostMapping("/auth/login")
    public AdminTokenResponse login(@Valid @RequestBody AdminLoginRequest request) {
        AdminAuthService.AdminLoginResult result = adminAuthService.login(request.email(), request.password());
        return new AdminTokenResponse(result.accessToken(), "Bearer", result.expiresInSeconds(), result.role());
    }

    @GetMapping("/me")
    public AdminProfileResponse me(@AuthenticationPrincipal Jwt jwt) {
        AuthenticatedUser user = AuthenticatedUser.from(jwt);
        AdminUser admin = adminAuthService.getById(user.id());
        return new AdminProfileResponse(admin.getId(), admin.getEmail(), admin.getFullName(), admin.getRole());
    }

    public record AdminLoginRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 72) String password) {}

    public record AdminTokenResponse(String accessToken, String tokenType, long expiresInSeconds, AdminRole role) {}

    public record AdminProfileResponse(Long id, String email, String fullName, AdminRole role) {}
}
