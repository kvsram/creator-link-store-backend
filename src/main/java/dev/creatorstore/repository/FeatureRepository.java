package dev.creatorstore.repository;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class FeatureRepository {
  private final JdbcTemplate database;

  public FeatureRepository(JdbcTemplate database) {
    this.database = database;
  }

  public List<Map<String, Object>> funnels(long creatorId) {
    return database.queryForList(
        "select id,name,status from funnels where creator_id=? order by id", creatorId);
  }

  public List<Map<String, Object>> appointments(long creatorId) {
    return database.queryForList(
        "select b.id,b.starts_at,b.ends_at,b.status from bookings b join availability_schedules s on s.id=b.schedule_id where s.creator_id=? order by b.starts_at",
        creatorId);
  }

  public Map<String, Object> notifications(long creatorId) {
    return database.queryForMap(
        "select order_emails,marketing_emails,payout_emails from notification_preferences where creator_id=?",
        creatorId);
  }

  public void createNotificationDefaults(long creatorId) {
    database.update("insert into notification_preferences(creator_id) values(?)", creatorId);
  }
}
