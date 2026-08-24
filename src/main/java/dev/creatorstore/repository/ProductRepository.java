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
        "select id,type,title,description,price_cents as price_subunits,price_cents,status,position,thumbnail_url,pinned,subtitle,call_to_action,thumbnail_style,configuration_json from products where creator_id=? order by pinned desc,position,id",
        creatorId);
  }

  public Map<String, Object> create(long creatorId, String type, String title, String description,
                                    int priceSubunits, String status, int position) {
    database.update("insert into products(creator_id,type,title,description,price_cents,status,position) values(?,?,?,?,?,?,?)",
        creatorId, type, title, description, priceSubunits, status, position);
    long id = database.queryForObject(
        "select max(id) from products where creator_id=?", Long.class, creatorId);
    return database.queryForMap(
        "select id,type,title,description,price_cents as price_subunits,price_cents,status,position,pinned,subtitle,call_to_action,thumbnail_style,configuration_json from products where id=?",
        id);
  }

  public List<Map<String, Object>> update(long creatorId, long id, String type, String title,
      String description, int priceSubunits, String status, int position) {
    database.update("update products set type=?,title=?,description=?,price_cents=?,status=?,position=? where id=? and creator_id=?",
        type, title, description, priceSubunits, status, position, id, creatorId);
    return database.queryForList(
        "select id,type,title,description,price_cents as price_subunits,price_cents,status,position,thumbnail_url,pinned,subtitle,call_to_action,thumbnail_style,configuration_json from products where id=? and creator_id=?",
        id, creatorId);
  }

  public int delete(long creatorId, long id) {
    return database.update("delete from products where id=? and creator_id=?", id, creatorId);
  }

  public List<Map<String, Object>> setPinned(long creatorId, long id, boolean pinned) {
    database.update("update products set pinned=? where id=? and creator_id=?", pinned, id, creatorId);
    return database.queryForList(
        "select id,type,title,description,price_cents as price_subunits,price_cents,status,position,thumbnail_url,pinned from products where id=? and creator_id=?",
        id, creatorId);
  }

  public List<Map<String, Object>> findOwned(long creatorId, long id) {
    return database.queryForList("select id,type,status from products where id=? and creator_id=?", id, creatorId);
  }

  public Map<String, Object> updateConfiguration(long creatorId, long id, String subtitle,
      String callToAction, String thumbnailStyle, String configurationJson) {
    database.update("update products set subtitle=?,call_to_action=?,thumbnail_style=?,configuration_json=? where id=? and creator_id=?",
        subtitle, callToAction, thumbnailStyle, configurationJson, id, creatorId);
    return database.queryForMap(
        "select id,type,title,subtitle,call_to_action,thumbnail_style,configuration_json from products where id=? and creator_id=?",
        id, creatorId);
  }

  public Map<String, Object> addFile(long productId, String fileName, String objectKey,
      String contentType, long sizeBytes, String kind) {
    return database.queryForMap(
        "insert into product_files(product_id,file_name,object_key,content_type,size_bytes,kind) values(?,?,?,?,?,?) returning id,product_id,file_name,content_type,size_bytes,kind,created_at",
        productId, fileName, objectKey, contentType, sizeBytes, kind);
  }

  public List<Map<String, Object>> files(long creatorId, long productId) {
    return database.queryForList(
        "select f.id,f.file_name,f.content_type,f.size_bytes,f.kind,f.created_at from product_files f join products p on p.id=f.product_id where f.product_id=? and p.creator_id=? order by f.id",
        productId, creatorId);
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
