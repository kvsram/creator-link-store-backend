package dev.creatorstore.repository;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class IntegrationRepository {
  private final JdbcTemplate database;

  public IntegrationRepository(JdbcTemplate database) {
    this.database = database;
  }

  public List<Map<String, Object>> findAll(long creatorId) {
    return database.queryForList(
        "select id,provider,status,external_account_label from integrations where creator_id=? order by provider",
        creatorId);
  }
}
