package dev.creatorstore.dto;

import java.util.Map;

public record CheckoutRequest(
    long creatorId,
    long productId,
    String provider,
    String buyerEmail,
    String buyerName,
    Map<String, String> fieldResponses,
    Long slotId,
    Long planId) {}
