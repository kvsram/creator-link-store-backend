package dev.creatorstore.repository;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AutomationRepository {
  private final JdbcTemplate database;

  public AutomationRepository(JdbcTemplate database) {
    this.database = database;
  }

  public List<Map<String, Object>> findStats(long automationId) {
    return database.queryForList(
        "select automation_id,comments_seen,messages_sent,link_clicks,updated_at from automation_stats where automation_id=?",
        automationId);
  }
}
