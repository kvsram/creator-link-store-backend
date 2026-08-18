package dev.creatorstore.integration;

public record PaymentProviderCommand(String checkoutId, String title, int amountSubunits,
                                     String currency, String idempotencyKey) {}
