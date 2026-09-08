package dev.creatorstore.repository;

import java.util.List;
import java.util.LinkedHashMap;
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
      int priceSubunits, String status, int position, String subtitle, String callToAction,
      String thumbnailStyle, String configurationJson, String idempotencyKey) {
    List<Map<String, Object>> inserted = database.queryForList(
        "insert into products(creator_id,type,title,description,price_cents,status,position,subtitle,call_to_action,thumbnail_style,configuration_json,idempotency_key) "
            + "values(?,?,?,?,?,?,?,?,?,?,?,?) on conflict(creator_id,idempotency_key) where idempotency_key is not null do nothing "
            + "returning id,type,title,description,price_cents as price_subunits,price_cents,status,position,pinned,subtitle,call_to_action,thumbnail_style,configuration_json",
        creatorId, type, title, description, priceSubunits, status, position, subtitle,
        callToAction, thumbnailStyle, configurationJson, idempotencyKey);
    if (!inserted.isEmpty()) {
      Map<String, Object> created = new LinkedHashMap<>(inserted.get(0));
      created.put("_created", true);
      return created;
    }
    Map<String, Object> existing = new LinkedHashMap<>(database.queryForMap(
        "select id,type,title,description,price_cents as price_subunits,price_cents,status,position,pinned,subtitle,call_to_action,thumbnail_style,configuration_json "
            + "from products where creator_id=? and idempotency_key=?",
        creatorId, idempotencyKey));
    existing.put("_created", false);
    return existing;
  }

  public List<Map<String, Object>> update(long creatorId, long id, String title,
      String description, int priceSubunits, String status, int position, String subtitle,
      String callToAction, String thumbnailStyle) {
    database.update("update products set title=?,description=?,price_cents=?,status=?,position=?,subtitle=?,call_to_action=?,thumbnail_style=? where id=? and creator_id=?",
        title, description, priceSubunits, status, position, subtitle, callToAction,
        thumbnailStyle, id, creatorId);
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
    return database.queryForList(
        "select id,type,title,description,price_cents as price_subunits,status,position,pinned,subtitle,call_to_action,thumbnail_style,configuration_json from products where id=? and creator_id=?",
        id, creatorId);
  }

  public List<Map<String, Object>> findOwnedDetails(long creatorId, long id) {
    return database.queryForList(
        "select id,type,title,description,price_cents as price_subunits,status,position,thumbnail_url,pinned,subtitle,call_to_action,thumbnail_style,configuration_json "
            + "from products where id=? and creator_id=?",
        id, creatorId);
  }

  public List<Map<String, Object>> findPublic(String handle, long id) {
    return database.queryForList(
        "select p.id,p.creator_id,p.type,p.title,p.description,p.price_cents as price_subunits,p.status,p.position,p.pinned,p.subtitle,p.call_to_action,p.thumbnail_style,p.thumbnail_url,p.configuration_json,s.currency "
            + "from products p join creators c on c.id=p.creator_id join stores s on s.creator_id=p.creator_id "
            + "where lower(c.handle)=lower(?) and p.id=? and p.status='published' and s.published=true",
        handle, id);
  }

  public Map<String, Object> updateConfiguration(long creatorId, long id, String subtitle,
      String callToAction, String thumbnailStyle, String configurationJson) {
    database.update("update products set subtitle=?,call_to_action=?,thumbnail_style=?,configuration_json=? where id=? and creator_id=?",
        subtitle, callToAction, thumbnailStyle, configurationJson, id, creatorId);
    return database.queryForMap(
        "select id,type,title,subtitle,call_to_action,thumbnail_style,configuration_json from products where id=? and creator_id=?",
        id, creatorId);
  }

  public long countFiles(long creatorId, long productId, String kind) {
    Number value = database.queryForObject(
        "select count(*) from product_files f join products p on p.id=f.product_id where p.creator_id=? and p.id=? and f.kind=?",
        Number.class, creatorId, productId, kind);
    return value == null ? 0 : value.longValue();
  }

  public List<Map<String, Object>> findOwnedFile(long creatorId, long productId, long fileId) {
    return database.queryForList(
        "select f.id,f.product_id,f.file_name,f.object_key,f.kind,p.type,p.status from product_files f join products p on p.id=f.product_id where p.creator_id=? and p.id=? and f.id=?",
        creatorId, productId, fileId);
  }

  public int deleteFile(long creatorId, long productId, long fileId) {
    return database.update(
        "delete from product_files f using products p where f.id=? and f.product_id=? and p.id=f.product_id and p.creator_id=?",
        fileId, productId, creatorId);
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
        "select p.id,p.creator_id,p.type,p.title,p.price_cents as amount_subunits,s.currency,c.handle from products p join stores s on s.creator_id=p.creator_id join creators c on c.id=p.creator_id where p.id=? and p.creator_id=? and p.status='published'",
        productId, creatorId);
  }

  public long count(long creatorId) {
    Number value = database.queryForObject(
        "select count(*) from products where creator_id=?", Number.class, creatorId);
    return value == null ? 0 : value.longValue();
  }
}
