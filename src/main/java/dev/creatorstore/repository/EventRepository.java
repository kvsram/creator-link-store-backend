package dev.creatorstore.repository;

import java.util.List;
import java.util.Map;
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

  public List<Map<String, Object>> findPublishedCreatorByHandle(String handle) {
    return database.queryForList(
        "select c.id as \"id\",c.handle as \"handle\" from creators c "
            + "join stores s on s.creator_id=c.id "
            + "where lower(c.handle)=lower(?) and s.published=true",
        handle);
  }

  public List<Map<String, Object>> findPublishedCreatorById(long creatorId) {
    return database.queryForList(
        "select c.id as \"id\",c.handle as \"handle\" from creators c "
            + "join stores s on s.creator_id=c.id where c.id=? and s.published=true",
        creatorId);
  }

  public void recordClick(long linkId, long creatorId, String path, String referrer,
      String userAgent, String campaign) {
    database.update("insert into click_events(link_id,creator_id,path,referrer,user_agent,campaign) values(?,?,?,?,?,?)",
        linkId, creatorId, path, referrer, userAgent, campaign);
  }
}
