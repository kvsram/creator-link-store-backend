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
        "select title,tagline,theme,currency,accent_color,background_style,button_style,font_style,show_products,show_links from stores where creator_id=? and published=true",
        creatorId);
  }

  public List<Map<String, Object>> findPublishedLinks(long creatorId) {
    return database.queryForList(
        "select id,title,url,description,brand_name,thumbnail_url,call_to_action,coupon_code,offer_text,disclosure,position,pinned,starts_at,ends_at from links where creator_id=? and published=true and (starts_at is null or starts_at<=current_timestamp) and (ends_at is null or ends_at>current_timestamp) order by pinned desc,position,id", creatorId);
  }

  public List<Map<String, Object>> findPublishedProducts(long creatorId) {
    return database.queryForList(
        "select id,type,title,subtitle,call_to_action,thumbnail_style,description,price_cents as price_subunits,price_cents,thumbnail_url,pinned,position,configuration_json from products where creator_id=? and status='published' order by pinned desc,position,id",
        creatorId);
  }

  public Map<String, Object> findSummary(long creatorId) {
    return first("select title,published,payouts_enabled from stores where creator_id=?", creatorId);
  }

  public Map<String, Object> findDetails(long creatorId) {
    return first("select id,title,tagline,theme,currency,published,payouts_enabled,accent_color,background_style,button_style,font_style,show_products,show_links from stores where creator_id=?", creatorId);
  }

  public Map<String, Object> findSettings(long creatorId) {
    return first("select title,tagline,theme,currency,published,payouts_enabled,accent_color,background_style,button_style,font_style,show_products,show_links from stores where creator_id=?", creatorId);
  }

  public String findCurrency(long creatorId) {
    return String.valueOf(first("select currency from stores where creator_id=?", creatorId)
        .getOrDefault("currency", "INR"));
  }

  public void createDefault(long creatorId, String title) {
    database.update("insert into stores(creator_id,title,currency) values(?,?,?)", creatorId, title, "INR");
  }

  public Map<String, Object> updateDesign(long creatorId, String title, String tagline,
      String theme, String accentColor, String backgroundStyle, String buttonStyle,
      String fontStyle, boolean showProducts, boolean showLinks) {
    database.update("update stores set title=?,tagline=?,theme=?,accent_color=?,background_style=?,button_style=?,font_style=?,show_products=?,show_links=? where creator_id=?",
        title, tagline, theme, accentColor, backgroundStyle, buttonStyle, fontStyle,
        showProducts, showLinks, creatorId);
    return findDetails(creatorId);
  }

  private Map<String, Object> first(String sql, Object... args) {
    List<Map<String, Object>> rows = database.queryForList(sql, args);
    return rows.isEmpty() ? Map.of() : rows.get(0);
  }
}
