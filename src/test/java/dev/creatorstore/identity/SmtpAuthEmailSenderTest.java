package dev.creatorstore.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

class SmtpAuthEmailSenderTest {
  @Test
  void verificationCredentialIsPlacedInAUrlFragment() {
    JavaMailSender mail = mock(JavaMailSender.class);
    SmtpAuthEmailSender sender = new SmtpAuthEmailSender(mail,
        "https://creators.example/", "security@creators.example", "Creator Store");

    sender.send(delivery("email_verification", "secret+/="));

    ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
    verify(mail).send(sent.capture());
    assertThat(sent.getValue().getText())
        .contains("https://creators.example/verify-email#token=secret%2B%2F%3D")
        .doesNotContain("verify-email?token=");
  }

  @Test
  void resetCredentialIsPlacedInAUrlFragment() {
    JavaMailSender mail = mock(JavaMailSender.class);
    SmtpAuthEmailSender sender = new SmtpAuthEmailSender(mail,
        "https://creators.example", "security@creators.example", "Creator Store");

    sender.send(delivery("password_reset", "reset token"));

    ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
    verify(mail).send(sent.capture());
    assertThat(sent.getValue().getText())
        .contains("https://creators.example/reset-password#token=reset+token")
        .doesNotContain("reset-password?token=");
  }

  private static AuthDeliveryRepository.Delivery delivery(String type, String token) {
    return new AuthDeliveryRepository.Delivery(UUID.randomUUID(), 41L, type,
        "creator@example.com", token, OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(30),
        "leased", 1, UUID.randomUUID());
  }
}
