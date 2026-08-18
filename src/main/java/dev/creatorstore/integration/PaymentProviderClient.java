package dev.creatorstore.integration;

public interface PaymentProviderClient {
  String provider();
  boolean configured();
  ProviderSession createSession(PaymentProviderCommand command) throws Exception;
}
