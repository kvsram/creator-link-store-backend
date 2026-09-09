package dev.creatorstore.repository;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class LeadRepository {
  private final JdbcTemplate database;

  public LeadRepository(JdbcTemplate database) {
    this.database = database;
  }

  public List<Map<String, Object>> findPublishedLeadMagnet(long productId) {
    return database.queryForList(
        "select p.id as \"id\",p.creator_id as \"creator_id\","
            + "p.configuration_json as \"configuration_json\" "
            + "from products p join stores s on s.creator_id=p.creator_id "
            + "where p.id=? and p.type='lead-magnet' and p.status='published' "
            + "and s.published=true",
        productId);
  }

  public List<Map<String, Object>> findByIdempotencyKey(long productId, String key) {
    return database.queryForList(
        "select request_fingerprint as \"request_fingerprint\" from leads "
            + "where product_id=? and idempotency_key=?",
        productId, key);
  }

  public void create(long creatorId, long productId, String email, String name, String phone,
      boolean consentGiven, String consentText, String idempotencyKey, String fingerprint) {
    database.update(
        "insert into leads(creator_id,product_id,email,name,phone,consent_given,consent_text,"
            + "idempotency_key,request_fingerprint) values(?,?,?,?,?,?,?,?,?)",
        creatorId, productId, email, name, phone, consentGiven, consentText, idempotencyKey,
        fingerprint);
  }
}
