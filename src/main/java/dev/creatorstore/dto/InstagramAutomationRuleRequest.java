package dev.creatorstore.dto;

import java.util.List;

public record InstagramAutomationRuleRequest(
    String instagramAccountId, String mediaId, List<String> keywords,
    String matchMode, String replyText, boolean active) {}
