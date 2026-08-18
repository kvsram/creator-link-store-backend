package dev.creatorstore.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class EventRepository {
  private final JdbcTemplate database;

  public EventRepository(JdbcTemplate database) {
    this.database = database;
  }

  public void recordPageView(long creatorId, String path, String referrer) {
    database.update("insert into store_visits(creator_id,path,referrer) values(?,?,?)",
        creatorId, path, referrer);
  }

  public void recordClick(long linkId, String referrer) {
    database.update("insert into click_events(link_id,referrer) values(?,?)", linkId, referrer);
  }
}
