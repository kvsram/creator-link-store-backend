package com.creatorstore.verification;

import com.creatorstore.common.ApiException;
import com.creatorstore.common.Tokens;
import com.creatorstore.config.AppProperties;
import com.creatorstore.creator.Creator;
import com.creatorstore.creator.CreatorService;
import com.creatorstore.notification.SmsSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * Phone verification by one-time link.
 *
 * Flow: issueLink() -> SMS with raw token -> user opens frontend page -> frontend POSTs token -> confirm().
 */
@Service
public class PhoneVerificationService {

    private static final Logger log = LoggerFactory.getLogger(PhoneVerificationService.class);

    private final PhoneVerificationTokenRepository tokens;
    private final CreatorService creatorService;
    private final SmsSender smsSender;
    private final AppProperties.Verification policy;
    private final String baseUrl;

    public PhoneVerificationService(PhoneVerificationTokenRepository tokens,
                                    CreatorService creatorService,
                                    SmsSender smsSender,
                                    AppProperties props) {
        this.tokens = tokens;
        this.creatorService = creatorService;
        this.smsSender = smsSender;
        this.policy = props.verification();
        this.baseUrl = props.baseUrl();
    }

    /**
     * Issues a new link for the creator's current phone number and closes any older open link.
     *
     * @return false when the per-phone hourly limit was reached (no SMS sent); the caller decides what to tell the user.
     * @throws ApiException PHONE_REQUIRED if the creator has no phone number.
     */
    @Transactional
    public boolean issueLink(Long creatorId) {
        Creator creator = creatorService.getById(creatorId);
        String phone = creator.getPhoneNumber();
        if (phone == null) {
            throw ApiException.badRequest("PHONE_REQUIRED", "Add a phone number before requesting verification.");
        }

        Instant now = Instant.now();
        long recent = tokens.countByPhoneNumberAndCreatedAtAfter(phone, now.minus(Duration.ofHours(1)));
        if (recent >= policy.maxLinksPerPhonePerHour()) {
            log.info("SMS rate limit hit for creator {}", creatorId);
            return false;
        }

        tokens.closeOpenTokens(creatorId, now);

        String rawToken = Tokens.newRawToken();
        tokens.save(new PhoneVerificationToken(
                creatorId, phone, Tokens.sha256(rawToken), now, now.plus(policy.linkTtl())));

        smsSender.send(phone, "Verify your phone: " + baseUrl + "/verify-phone?token=" + rawToken);
        return true;
    }

    /** Called by the frontend verification page with the token from the URL. Idempotency: a used link is rejected. */
    @Transactional
    public void confirm(String rawToken) {
        PhoneVerificationToken token = tokens.findByTokenHash(Tokens.sha256(rawToken))
                .orElseThrow(() -> ApiException.badRequest("INVALID_TOKEN", "This link is not valid."));

        Instant now = Instant.now();
        if (token.isUsed()) {
            throw ApiException.badRequest("LINK_USED_OR_REPLACED",
                    "This link was already used or replaced. Please use the latest link.");
        }
        if (token.isExpired(now)) {
            throw ApiException.badRequest("LINK_EXPIRED", "This link has expired. Please request a new one.");
        }

        token.markUsed(now);
        creatorService.markPhoneVerified(token.getCreatorId(), token.getPhoneNumber(), now);
    }

    /** Daily housekeeping: rows older than 7 days past expiry carry no information worth keeping. */
    @Scheduled(cron = "0 15 3 * * *")
    @Transactional
    public void deleteOldTokens() {
        int deleted = tokens.deleteExpiredBefore(Instant.now().minus(Duration.ofDays(7)));
        if (deleted > 0) {
            log.info("Deleted {} expired phone verification tokens", deleted);
        }
    }
}
