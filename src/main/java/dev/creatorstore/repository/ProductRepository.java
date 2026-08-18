package dev.creatorstore.repository;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ProductRepository {
  private final JdbcTemplate database;

  public ProductRepository(JdbcTemplate database) {
    this.database = database;
  }

  public List<Map<String, Object>> findAll(long creatorId) {
    return database.queryForList(
        "select id,type,title,description,price_cents as price_subunits,price_cents,status,position,thumbnail_url from products where creator_id=? order by position,id",
        creatorId);
  }

  public Map<String, Object> create(long creatorId, String type, String title, String description,
                                    int priceSubunits, String status, int position) {
    database.update("insert into products(creator_id,type,title,description,price_cents,status,position) values(?,?,?,?,?,?,?)",
        creatorId, type, title, description, priceSubunits, status, position);
    long id = database.queryForObject(
        "select max(id) from products where creator_id=?", Long.class, creatorId);
    return database.queryForMap(
        "select id,type,title,description,price_cents as price_subunits,price_cents,status,position from products where id=?",
        id);
  }

  public List<Map<String, Object>> findCheckoutProduct(long productId, long creatorId) {
    return database.queryForList(
        "select p.id,p.creator_id,p.title,p.price_cents as amount_subunits,s.currency,c.handle from products p join stores s on s.creator_id=p.creator_id join creators c on c.id=p.creator_id where p.id=? and p.creator_id=? and p.status='published'",
        productId, creatorId);
  }

  public long count(long creatorId) {
    Number value = database.queryForObject(
        "select count(*) from products where creator_id=?", Number.class, creatorId);
    return value == null ? 0 : value.longValue();
  }
}
