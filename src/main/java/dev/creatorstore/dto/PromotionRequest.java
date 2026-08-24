package dev.creatorstore.dto;

import java.time.OffsetDateTime;

public record PromotionRequest(
    String title,
    String url,
    String description,
    String brandName,
    String thumbnailUrl,
    String callToAction,
    String couponCode,
    String offerText,
    String disclosure,
    Integer position,
    Boolean published,
    OffsetDateTime startsAt,
    OffsetDateTime endsAt) {}
