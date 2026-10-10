package dev.creatorstore.repository;

import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TagRepository {
  private final JdbcTemplate database;

  public TagRepository(JdbcTemplate database) {
    this.database = database;
  }

  public Map<String, Object> upsert(long creatorId, String name) {
    try {
      database.update("insert into tags(creator_id,name) values(?,?)", creatorId, name);
    } catch (DuplicateKeyException alreadyExists) {
      // The unique (creator_id,name) constraint makes retrying the request idempotent.
    }
    return database.queryForMap(
        "select id as \"id\",name as \"name\" from tags where creator_id=? and name=?",
        creatorId, name);
  }
}
