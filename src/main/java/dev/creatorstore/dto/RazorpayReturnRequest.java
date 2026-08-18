package dev.creatorstore.dto;

public record RazorpayReturnRequest(String orderId, String paymentId, String signature) {}
