package dev.creatorstore.repository;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CreatorRepository {
  private final JdbcTemplate database;

  public CreatorRepository(JdbcTemplate database) {
    this.database = database;
  }

  public List<Map<String, Object>> findPublicCreator(String handle) {
    return database.queryForList(
        "select id as \"id\",handle as \"handle\",display_name as \"display_name\","
            + "bio as \"bio\",avatar_url as \"avatar_url\" from creators where handle=?",
        handle);
  }

  public Map<String, Object> findUser(String handle) {
    return first("select id as \"id\",handle as \"username\","
        + "display_name as \"display_name\",email as \"email\",phone as \"phone\","
        + "bio as \"bio\",avatar_url as \"avatar_url\",created_at as \"created_at\" "
        + "from creators where handle=?", handle);
  }

  public Map<String, Object> findProfile(long creatorId) {
    return first("select id as \"id\",handle as \"username\","
        + "display_name as \"display_name\",email as \"email\",phone as \"phone\","
        + "bio as \"bio\",avatar_url as \"avatar_url\" from creators where id=?",
        creatorId);
  }

  public boolean handleExists(String handle) {
    return database.queryForObject("select count(*) from creators where handle=?", Integer.class, handle) > 0;
  }

  public boolean handleExistsForOtherCreator(String handle, long creatorId) {
    return database.queryForObject(
        "select count(*) from creators where handle=? and id<>?", Integer.class, handle,
        creatorId) > 0;
  }

  public boolean emailExists(String email) {
    return database.queryForObject("select count(*) from creators where email=?", Integer.class, email) > 0;
  }

  public long create(String handle, String displayName, String email, String phone, String passwordHash) {
    database.update("insert into creators(handle,display_name,email,phone,password_hash,bio) values(?,?,?,?,?,?)",
        handle, displayName, email, phone, passwordHash, "");
    return database.queryForObject("select id from creators where handle=?", Long.class, handle);
  }

  public int updateProfile(long creatorId, String handle, String displayName, String bio,
                           String phone) {
    return database.update(
        "update creators set handle=?,display_name=?,bio=?,phone=? where id=?",
        handle, displayName, bio, phone, creatorId);
  }

  private Map<String, Object> first(String sql, Object... args) {
    List<Map<String, Object>> rows = database.queryForList(sql, args);
    return rows.isEmpty() ? Map.of() : rows.get(0);
  }
}
