package dev.creatorstore.repository;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CheckoutRepository {
  private final JdbcTemplate database;

  public CheckoutRepository(JdbcTemplate database) {
    this.database = database;
  }

  public List<Map<String, Object>> findByIdempotencyKey(long creatorId, String idempotencyKey) {
    return database.queryForList(
        "select id,provider,provider_session_id,currency,amount_subunits,status from checkout_sessions where creator_id=? and idempotency_key=?",
        creatorId, idempotencyKey);
  }

  public void create(String checkoutId, long creatorId, long productId, String provider,
                     String idempotencyKey, String currency, int amountSubunits) {
    database.update("insert into checkout_sessions(id,creator_id,product_id,provider,idempotency_key,currency,amount_subunits,status) values(?,?,?,?,?,?,?,?)",
        checkoutId, creatorId, productId, provider, idempotencyKey, currency, amountSubunits, "creating");
  }

  public Map<String, Object> findOneByIdempotencyKey(long creatorId, String idempotencyKey) {
    return database.queryForMap(
        "select id,provider,provider_session_id,currency,amount_subunits,status from checkout_sessions where creator_id=? and idempotency_key=?",
        creatorId, idempotencyKey);
  }

  public void providerCreated(String checkoutId, String providerSessionId) {
    database.update("update checkout_sessions set provider_session_id=?,status='provider_created',updated_at=current_timestamp where id=?",
        providerSessionId, checkoutId);
  }

  public void providerFailed(String checkoutId) {
    database.update("update checkout_sessions set status='provider_failed',updated_at=current_timestamp where id=?",
        checkoutId);
  }

  public void browserVerified(String razorpayOrderId) {
    database.update("update checkout_sessions set status='browser_verified',updated_at=current_timestamp where provider='razorpay' and provider_session_id=?",
        razorpayOrderId);
  }

  public void paid(String provider, String providerSessionId) {
    database.update("update checkout_sessions set status='paid',updated_at=current_timestamp where provider=? and provider_session_id=?",
        provider, providerSessionId);
  }
}
