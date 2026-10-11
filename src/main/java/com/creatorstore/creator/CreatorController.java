package com.creatorstore.creator;

import com.creatorstore.security.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints a logged-in creator uses for their own account. Requires ROLE_CREATOR.
 * Phone number changes live in the verification module (PUT /api/verification/phone),
 * because changing the number always means re-verifying it.
 */
@RestController
@RequestMapping("/api/creators")
public class CreatorController {

    private final CreatorService creatorService;

    public CreatorController(CreatorService creatorService) {
        this.creatorService = creatorService;
    }

    @GetMapping("/me")
    public CreatorProfileResponse me(@AuthenticationPrincipal Jwt jwt) {
        AuthenticatedUser user = AuthenticatedUser.from(jwt);
        return CreatorProfileResponse.from(creatorService.getById(user.id()));
    }
}
