package dev.creatorstore.repository;

import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MetricsRepository {
  private final JdbcTemplate database;

  public MetricsRepository(JdbcTemplate database) {
    this.database = database;
  }

  public Map<String, Object> metrics(long creatorId) {
    return Map.of(
        "visits", count("select count(*) from store_visits where creator_id=?", creatorId),
        "leads", count("select count(*) from leads where creator_id=?", creatorId),
        "orders", count("select count(*) from orders where creator_id=? and status='paid'", creatorId),
        "revenue_subunits", count("select coalesce(sum(amount_cents),0) from orders where creator_id=? and status='paid'", creatorId));
  }

  private long count(String sql, Object... args) {
    Number value = database.queryForObject(sql, Number.class, args);
    return value == null ? 0 : value.longValue();
  }
}
