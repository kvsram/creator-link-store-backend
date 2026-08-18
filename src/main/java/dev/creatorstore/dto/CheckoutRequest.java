package dev.creatorstore.dto;

public record CheckoutRequest(long creatorId, long productId, String provider) {}
