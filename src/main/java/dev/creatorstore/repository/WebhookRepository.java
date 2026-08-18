package dev.creatorstore.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class WebhookRepository {
  private final JdbcTemplate database;

  public WebhookRepository(JdbcTemplate database) {
    this.database = database;
  }

  public void recordPaymentEvent(String provider, String hash, String type) {
    database.update("insert into provider_events(provider,event_key,event_type,payload_sha256,signature_verified) values(?,?,?,?,true)",
        provider, hash, type, hash);
  }

  public void recordInstagramEvent(String hash) {
    database.update("insert into instagram_events(event_key,payload_sha256,signature_verified) values(?,?,true)",
        hash, hash);
  }
}
