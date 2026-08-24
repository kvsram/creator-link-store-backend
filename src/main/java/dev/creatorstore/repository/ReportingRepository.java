package dev.creatorstore.repository;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ReportingRepository {
  private final JdbcTemplate database;

  public ReportingRepository(JdbcTemplate database) {
    this.database = database;
  }

  public Map<String, Object> incomeSummary(long creatorId) {
    return database.queryForMap(
        "select coalesce(sum(amount_cents),0) as gross_subunits,coalesce(sum(fee_cents),0) as fees_subunits,coalesce(sum(amount_cents-fee_cents),0) as net_subunits,count(*) as order_count from orders where creator_id=? and status='paid'",
        creatorId);
  }

  public List<Map<String, Object>> orders(long creatorId) {
    return database.queryForList(
        "select o.id,o.amount_cents as amount_subunits,o.fee_cents as fee_subunits,o.status,o.created_at,c.name as customer,p.title as product from orders o left join customers c on c.id=o.customer_id left join products p on p.id=o.product_id where o.creator_id=? order by o.created_at desc limit 100",
        creatorId);
  }

  public List<Map<String, Object>> trafficSources(long creatorId) {
    return database.queryForList(
        "select coalesce(referrer,'direct') as source,count(*) as visits from store_visits where creator_id=? group by coalesce(referrer,'direct') order by visits desc",
        creatorId);
  }

  public List<Map<String, Object>> promotionPerformance(long creatorId) {
    return database.queryForList(
        "select l.id,l.title,l.brand_name,l.published,count(c.id) as clicks,count(distinct coalesce(c.referrer,'')) as sources,max(c.occurred_at) as last_clicked_at from links l left join click_events c on c.link_id=l.id and c.creator_id=l.creator_id where l.creator_id=? group by l.id,l.title,l.brand_name,l.published order by clicks desc,l.position,l.id",
        creatorId);
  }
}
