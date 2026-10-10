package dev.creatorstore.identity;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/** Provider-neutral SMTP adapter; AWS SES SMTP is configured entirely through environment data. */
@Service
public class SmtpAuthEmailSender implements AuthEmailSender {
  private final JavaMailSender mail;
  private final String publicBaseUrl;
  private final String from;
  private final String fromName;

  public SmtpAuthEmailSender(JavaMailSender mail,
      @Value("${app.auth.public-base-url:http://localhost:5173}") String publicBaseUrl,
      @Value("${app.auth.email-from:}") String from,
      @Value("${app.auth.email-from-name:Creator Store}") String fromName) {
    this.mail = mail;
    this.publicBaseUrl = withoutTrailingSlash(publicBaseUrl);
    this.from = from;
    this.fromName = fromName;
  }

  @Override
  public void send(AuthDeliveryRepository.Delivery delivery) {
    if (from == null || from.isBlank()) {
      throw new IllegalStateException("APP_AUTH_EMAIL_FROM is required for SMTP delivery");
    }
    Template template = template(delivery);
    SimpleMailMessage message = new SimpleMailMessage();
    message.setFrom(fromName + " <" + from + ">");
    message.setTo(delivery.recipient());
    message.setSubject(template.subject());
    message.setText(template.body());
    mail.send(message);
  }

  private Template template(AuthDeliveryRepository.Delivery delivery) {
    String token = URLEncoder.encode(delivery.token(), StandardCharsets.UTF_8);
    if ("email_verification".equals(delivery.type())) {
      // Keep one-time credentials out of query strings. URL fragments are not sent in HTTP
      // requests, proxy access logs, or Referer headers; the browser client consumes and scrubs it.
      String link = publicBaseUrl + "/verify-email#token=" + token;
      return new Template("Verify your Creator Store email",
          "Welcome to Creator Store.\n\nVerify your email using this one-time link:\n"
              + link + "\n\nThis link expires at " + delivery.expiresAt()
              + ". If you did not create this account, ignore this message.");
    }
    if ("password_reset".equals(delivery.type())) {
      String link = publicBaseUrl + "/reset-password#token=" + token;
      return new Template("Reset your Creator Store password",
          "A password reset was requested for your Creator Store account.\n\n"
              + "Choose a new password using this one-time link:\n" + link
              + "\n\nThis link expires at " + delivery.expiresAt()
              + ". If you did not request this, you can ignore this message.");
    }
    throw new IllegalArgumentException("Unsupported auth delivery type");
  }

  private static String withoutTrailingSlash(String value) {
    if (value == null || value.isBlank()) return "http://localhost:5173";
    String result = value.trim();
    while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
    return result;
  }

  private record Template(String subject, String body) {}
}
