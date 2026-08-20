package dev.creatorstore.repository;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class StoreRepository {
  private final JdbcTemplate database;

  public StoreRepository(JdbcTemplate database) {
    this.database = database;
  }

  public List<Map<String, Object>> findPublished(long creatorId) {
    return database.queryForList(
        "select title,theme,currency from stores where creator_id=? and published=true", creatorId);
  }

  public List<Map<String, Object>> findPublishedLinks(long creatorId) {
    return database.queryForList(
        "select id,title,url from links where creator_id=? and published=true order by position,id", creatorId);
  }

  public List<Map<String, Object>> findPublishedProducts(long creatorId) {
    return database.queryForList(
        "select id,type,title,description,price_cents as price_subunits,price_cents,thumbnail_url from products where creator_id=? and status='published' order by position,id",
        creatorId);
  }

  public Map<String, Object> findSummary(long creatorId) {
    return first("select title,published,payouts_enabled from stores where creator_id=?", creatorId);
  }

  public Map<String, Object> findDetails(long creatorId) {
    return first("select id,title,theme,currency,published,payouts_enabled from stores where creator_id=?", creatorId);
  }

  public Map<String, Object> findSettings(long creatorId) {
    return first("select title,theme,currency,published,payouts_enabled from stores where creator_id=?", creatorId);
  }

  public String findCurrency(long creatorId) {
    return String.valueOf(first("select currency from stores where creator_id=?", creatorId)
        .getOrDefault("currency", "INR"));
  }

  public void createDefault(long creatorId, String title) {
    database.update("insert into stores(creator_id,title,currency) values(?,?,?)", creatorId, title, "INR");
  }

  private Map<String, Object> first(String sql, Object... args) {
    List<Map<String, Object>> rows = database.queryForList(sql, args);
    return rows.isEmpty() ? Map.of() : rows.get(0);
  }
}
