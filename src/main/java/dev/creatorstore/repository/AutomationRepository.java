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

  public List<Map<String, Object>> findStats(long creatorId, long automationId) {
    return database.queryForList(
        "select s.automation_id,s.comments_seen,s.messages_sent,s.link_clicks,s.updated_at "
            + "from automation_stats s join automations a on a.id=s.automation_id "
            + "where s.automation_id=? and a.creator_id=?",
        automationId, creatorId);
  }
}
