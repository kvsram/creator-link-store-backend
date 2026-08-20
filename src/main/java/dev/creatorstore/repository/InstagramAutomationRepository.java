package dev.creatorstore.repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class InstagramAutomationRepository {
  private final JdbcTemplate database;

  public InstagramAutomationRepository(JdbcTemplate database) {
    this.database = database;
  }

  public long createRule(long creatorId, String accountId, String mediaId, String keywords,
      String matchMode, String message, boolean active) {
    database.update("insert into automations(creator_id,name,trigger_type,message,status) "
        + "values(?,?,'instagram_comment',?,?)", creatorId, "Comment DM for " + mediaId,
        message, active ? "active" : "draft");
    long automationId = database.queryForObject(
        "select max(id) from automations where creator_id=?", Long.class, creatorId);
    database.update("insert into instagram_automation_rules(automation_id,creator_id,instagram_account_id,"
        + "media_id,keywords,match_mode,active) values(?,?,?,?,?,?,?)", automationId, creatorId,
        accountId, mediaId, keywords, matchMode, active);
    database.update("insert into automation_stats(automation_id) values(?) on conflict do nothing", automationId);
    return automationId;
  }

  public List<Map<String, Object>> listRules(long creatorId) {
    return database.queryForList("select r.id,r.automation_id,r.instagram_account_id,r.media_id,"
        + "r.keywords,r.match_mode,r.active,a.message,a.status,r.created_at "
        + "from instagram_automation_rules r join automations a on a.id=r.automation_id "
        + "where r.creator_id=? order by r.id desc", creatorId);
  }

  public List<Map<String, Object>> matchingRules(String accountId, String mediaId) {
    return database.queryForList("select r.id,r.automation_id,r.keywords,r.match_mode,a.message "
        + "from instagram_automation_rules r join automations a on a.id=r.automation_id "
        + "where r.instagram_account_id=? and r.media_id=? and r.active=true and a.status='active'",
        accountId, mediaId);
  }

  public long insertComment(String eventId, String accountId, String mediaId, String commentId,
      String commenterId, String username, String text, Instant occurredAt) {
    database.update("insert into instagram_comment_events(provider_event_id,instagram_account_id,media_id,"
        + "comment_id,commenter_scoped_id,commenter_username,comment_text,occurred_at) values(?,?,?,?,?,?,?,?)",
        eventId, accountId, mediaId, commentId, commenterId, username, text, occurredAt);
    return database.queryForObject("select id from instagram_comment_events where comment_id=?",
        Long.class, commentId);
  }

  public void enqueue(long commentEventId, long automationId) {
    database.update("insert into instagram_dm_jobs(comment_event_id,automation_id) values(?,?) "
        + "on conflict(comment_event_id) do nothing", commentEventId, automationId);
    database.update("update automation_stats set comments_seen=comments_seen+1,updated_at=current_timestamp "
        + "where automation_id=?", automationId);
  }

  public List<Map<String, Object>> claimOne() {
    List<Map<String, Object>> rows = database.queryForList(
        "select j.id,j.attempt_count,e.comment_id,e.occurred_at,a.message,j.automation_id "
            + "from instagram_dm_jobs j join instagram_comment_events e on e.id=j.comment_event_id "
            + "join automations a on a.id=j.automation_id "
            + "where ((j.state in ('pending','retry') and j.next_attempt_at<=current_timestamp) "
            + "or (j.state='processing' and j.lease_until<current_timestamp)) "
            + "order by j.created_at for update skip locked limit 1");
    if (!rows.isEmpty()) {
      database.update("update instagram_dm_jobs set state='processing',attempt_count=attempt_count+1,"
          + "lease_until=current_timestamp + interval '2 minutes' where id=?", rows.get(0).get("id"));
    }
    return rows;
  }

  public void sent(long jobId, long automationId, String messageId) {
    database.update("update instagram_dm_jobs set state='sent',provider_message_id=?,sent_at=current_timestamp,"
        + "lease_until=null,last_error=null where id=?", messageId, jobId);
    database.update("update automation_stats set messages_sent=messages_sent+1,updated_at=current_timestamp "
        + "where automation_id=?", automationId);
  }

  public void retry(long jobId, int delaySeconds, String error) {
    database.update("update instagram_dm_jobs set state='retry',next_attempt_at=current_timestamp "
        + "+ (? || ' seconds')::interval,lease_until=null,last_error=? where id=?",
        delaySeconds, truncate(error), jobId);
  }

  public void dead(long jobId, String error) {
    database.update("update instagram_dm_jobs set state='dead',lease_until=null,last_error=? where id=?",
        truncate(error), jobId);
  }

  private static String truncate(String value) {
    if (value == null) return "unknown";
    return value.length() <= 500 ? value : value.substring(0, 500);
  }
}
