package dev.creatorstore.identity;

public interface AuthEmailSender {
  void send(AuthDeliveryRepository.Delivery delivery);
}
