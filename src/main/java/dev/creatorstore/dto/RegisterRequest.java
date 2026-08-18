package dev.creatorstore.dto;

public record RegisterRequest(String handle, String displayName, String email, String phone, String password) {}
