package dev.creatorstore.dto;

public record LeadCaptureRequest(Long creatorId, String email, String name, String phone,
                                 Boolean consent) {}
