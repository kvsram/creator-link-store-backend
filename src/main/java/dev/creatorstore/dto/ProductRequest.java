package dev.creatorstore.dto;

public record ProductRequest(long creatorId, String type, String title, String description,
                             int priceSubunits, String status, int position) {}
