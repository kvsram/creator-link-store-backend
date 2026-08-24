package dev.creatorstore.dto;

public record ClickEventRequest(long linkId, long creatorId, String path, String referrer,
                                String campaign) {}
