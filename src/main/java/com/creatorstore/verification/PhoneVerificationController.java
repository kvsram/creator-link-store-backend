package com.creatorstore.verification;

import com.creatorstore.creator.Creator;
import com.creatorstore.creator.CreatorService;
import com.creatorstore.security.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 *  POST /api/verification/phone/confirm   public (the SMS link lands here; the token is the proof)
 *  POST /api/verification/phone/resend    ROLE_CREATOR
 *  PUT  /api/verification/phone           ROLE_CREATOR (add or change the number, then send a link)
 */
@RestController
@RequestMapping("/api/verification/phone")
public class PhoneVerificationController {

    private final PhoneVerificationService verificationService;
    private final CreatorService creatorService;

    public PhoneVerificationController(PhoneVerificationService verificationService, CreatorService creatorService) {
        this.verificationService = verificationService;
        this.creatorService = creatorService;
    }

    /** POST (not GET) so SMS link previewers cannot consume the link by accident. */
    @PostMapping("/confirm")
    public ResponseEntity<Void> confirm(@Valid @RequestBody ConfirmRequest request) {
        verificationService.confirm(request.token());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/resend")
    public SendResponse resend(@AuthenticationPrincipal Jwt jwt) {
        AuthenticatedUser user = AuthenticatedUser.from(jwt);
        return new SendResponse(verificationService.issueLink(user.id()));
    }

    @PutMapping
    public PhoneUpdateResponse updatePhone(@AuthenticationPrincipal Jwt jwt,
                                           @Valid @RequestBody PhoneUpdateRequest request) {
        AuthenticatedUser user = AuthenticatedUser.from(jwt);
        Creator creator = creatorService.changePhoneNumber(user.id(), request.phoneNumber());
        boolean sent = verificationService.issueLink(creator.getId());
        return new PhoneUpdateResponse(creator.getPhoneNumber(), sent);
    }

    public record ConfirmRequest(@NotBlank @Size(max = 100) String token) {}

    public record SendResponse(boolean sent) {}

    public record PhoneUpdateRequest(@NotBlank @Size(max = 25) String phoneNumber) {}

    public record PhoneUpdateResponse(String phoneNumber, boolean verificationSent) {}
}
