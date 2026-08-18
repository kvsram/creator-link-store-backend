package dev.creatorstore.repository;

import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TagRepository {
  private final JdbcTemplate database;

  public TagRepository(JdbcTemplate database) {
    this.database = database;
  }

  public Map<String, Object> upsert(long creatorId, String name) {
    database.update("insert into tags(creator_id,name) values(?,?) on conflict(creator_id,name) do nothing",
        creatorId, name);
    return database.queryForMap("select id,name from tags where creator_id=? and name=?", creatorId, name);
  }
}
