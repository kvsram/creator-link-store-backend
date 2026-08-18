package dev.creatorstore.repository;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CustomerRepository {
  private final JdbcTemplate database;

  public CustomerRepository(JdbcTemplate database) {
    this.database = database;
  }

  public List<Map<String, Object>> findAll(long creatorId) {
    return database.queryForList(
        "select id,name,email,phone,source,created_at from customers where creator_id=? order by created_at desc limit 5000",
        creatorId);
  }

  public Map<String, Object> create(long creatorId, String name, String email, String phone) {
    database.update("insert into customers(creator_id,name,email,phone,source) values(?,?,?,?,?)",
        creatorId, name, email, phone, "manual");
    return database.queryForMap(
        "select id,name,email,phone,source,created_at from customers where creator_id=? and email=?",
        creatorId, email);
  }
}
