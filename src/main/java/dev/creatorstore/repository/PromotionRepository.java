package dev.creatorstore.repository;

import dev.creatorstore.dto.PromotionRequest;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PromotionRepository {
  private static final String FIELDS = "id,title,url,description,brand_name,thumbnail_url,call_to_action,coupon_code,offer_text,disclosure,position,published,pinned,starts_at,ends_at,updated_at";
  private final JdbcTemplate database;

  public PromotionRepository(JdbcTemplate database) {
    this.database = database;
  }

  public List<Map<String, Object>> findAll(long creatorId) {
    return database.queryForList("select " + FIELDS + " from links where creator_id=? order by pinned desc,position,id", creatorId);
  }

  public Map<String, Object> create(long creatorId, PromotionRequest request) {
    return database.queryForMap("insert into links(creator_id,title,url,description,brand_name,thumbnail_url,call_to_action,coupon_code,offer_text,disclosure,position,published,starts_at,ends_at) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?) returning " + FIELDS,
        creatorId, request.title(), request.url(), request.description(), request.brandName(),
        request.thumbnailUrl(), request.callToAction(), request.couponCode(), request.offerText(),
        request.disclosure(), request.position(), request.published(), request.startsAt(), request.endsAt());
  }

  public List<Map<String, Object>> update(long creatorId, long id, PromotionRequest request) {
    database.update("update links set title=?,url=?,description=?,brand_name=?,thumbnail_url=?,call_to_action=?,coupon_code=?,offer_text=?,disclosure=?,position=?,published=?,starts_at=?,ends_at=?,updated_at=current_timestamp where id=? and creator_id=?",
        request.title(), request.url(), request.description(), request.brandName(), request.thumbnailUrl(),
        request.callToAction(), request.couponCode(), request.offerText(), request.disclosure(),
        request.position(), request.published(), request.startsAt(), request.endsAt(), id, creatorId);
    return database.queryForList("select " + FIELDS + " from links where id=? and creator_id=?", id, creatorId);
  }

  public int delete(long creatorId, long id) {
    return database.update("delete from links where id=? and creator_id=?", id, creatorId);
  }

  public List<Map<String, Object>> setPinned(long creatorId, long id, boolean pinned) {
    database.update("update links set pinned=?,updated_at=current_timestamp where id=? and creator_id=?",
        pinned, id, creatorId);
    return database.queryForList("select " + FIELDS + " from links where id=? and creator_id=?", id, creatorId);
  }

  public boolean isTrackable(long creatorId, long id) {
    Number count = database.queryForObject("select count(*) from links where id=? and creator_id=? and published=true and (starts_at is null or starts_at<=current_timestamp) and (ends_at is null or ends_at>current_timestamp)", Number.class, id, creatorId);
    return count != null && count.longValue() == 1;
  }
}
