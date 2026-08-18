package dev.creatorstore;

import java.time.Instant;
import java.util.*;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

@SpringBootApplication
public class CreatorStoreApplication {
  public static void main(String[] args) { SpringApplication.run(CreatorStoreApplication.class, args); }

  @Bean CommandLineRunner seed(JdbcTemplate db) { return args -> {
    if (db.queryForObject("select count(*) from creators", Integer.class) != 0) return;
    String disabledDemoCredential = new BCryptPasswordEncoder().encode(UUID.randomUUID().toString());
    db.update("insert into creators(handle,display_name,email,password_hash,bio) values(?,?,?,?,?)",
        "alex", "Alex Rivera", "alex@example.test", disabledDemoCredential, "Systems and templates for independent creators.");
    long creatorId = db.queryForObject("select id from creators where handle='alex'", Long.class);
    db.update("insert into stores(creator_id,title,payouts_enabled) values(?,?,?)", creatorId, "Alex's Creator Store", true);
    db.update("insert into links(creator_id,title,url,position) values(?,?,?,?)", creatorId, "Free weekly newsletter", "https://example.com/newsletter", 1);
    db.update("insert into products(creator_id,type,title,description,price_cents,position) values(?,?,?,?,?,?)", creatorId, "digital-download", "Creator Content Calendar", "A practical 30-day content planning workbook.", 1900, 1);
    db.update("insert into products(creator_id,type,title,description,price_cents,position) values(?,?,?,?,?,?)", creatorId, "meeting", "Strategy Session", "A focused 45-minute planning call.", 9900, 2);
    long productId = db.queryForObject("select id from products where creator_id=? order by id limit 1", Long.class, creatorId);
    db.update("insert into customers(creator_id,name,email,source) values(?,?,?,?)", creatorId, "Jamie Chen", "jamie@example.test", "checkout");
    long customerId = db.queryForObject("select id from customers where creator_id=? limit 1", Long.class, creatorId);
    db.update("insert into orders(creator_id,customer_id,product_id,amount_cents,fee_cents,status,created_at) values(?,?,?,?,?,'paid',current_timestamp - interval '2 days')", creatorId, customerId, productId, 1900, 190);
    db.update("insert into leads(creator_id,product_id,email) values(?,?,?)", creatorId, productId, "reader@example.test");
    db.update("insert into store_visits(creator_id,path,referrer,occurred_at) values(?,?,?,current_timestamp - interval '1 day')", creatorId, "/alex", "instagram.com");
    for (String provider : List.of("stripe", "paypal", "mailchimp", "zoom", "google-calendar", "instagram"))
      db.update("insert into integrations(creator_id,provider,status) values(?,?,?)", creatorId, provider, provider.equals("stripe") ? "connected" : "disconnected");
    db.update("insert into notification_preferences(creator_id) values(?)", creatorId);
    db.update("insert into automations(creator_id,name,trigger_type,message,status) values(?,?,?,?,?)", creatorId, "Send free guide", "instagram_comment", "Thanks! Here is the guide: https://example.com/guide", "draft");
  }; }
}

@RestController
@CrossOrigin(origins = {"http://localhost:5173", "http://localhost:3000"})
class CreatorController {
  private final JdbcTemplate db;
  private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder();
  CreatorController(JdbcTemplate db) { this.db = db; }

  @GetMapping("/health") Map<String,String> health() { return Map.of("status", "ok"); }

  @GetMapping("/api/public/{handle}") ResponseEntity<?> publicPage(@PathVariable String handle) {
    List<Map<String,Object>> creators = db.queryForList("select id,handle,display_name,bio,avatar_url from creators where handle=?", handle.toLowerCase());
    if (creators.isEmpty()) return ResponseEntity.notFound().build();
    Map<String,Object> creator = creators.get(0); long id = number(creator.get("id"));
    List<Map<String,Object>> stores = db.queryForList("select title,theme,currency from stores where creator_id=? and published=true", id);
    if (stores.isEmpty()) return ResponseEntity.notFound().build();
    return ResponseEntity.ok(Map.of(
        "creator", creator,
        "store", stores.get(0),
        "links", db.queryForList("select id,title,url from links where creator_id=? and published=true order by position,id", id),
        "products", db.queryForList("select id,type,title,description,price_cents,thumbnail_url from products where creator_id=? and status='published' order by position,id", id)));
  }

  @RequestMapping(value="/api/v1/authentication/check-unique-taken", method=RequestMethod.OPTIONS)
  ResponseEntity<Void> uniqueOptions() { return ResponseEntity.noContent().build(); }

  @PostMapping("/api/v1/authentication/check-unique-taken") Map<String,Object> unique(@RequestBody Map<String,Object> body) {
    String handle = text(body.get("username")).toLowerCase(); String email = text(body.get("email")).toLowerCase();
    boolean usernameTaken = !handle.isBlank() && db.queryForObject("select count(*) from creators where handle=?", Integer.class, handle) > 0;
    boolean emailTaken = !email.isBlank() && db.queryForObject("select count(*) from creators where email=?", Integer.class, email) > 0;
    return Map.of("username_taken", usernameTaken, "email_taken", emailTaken, "available", !usernameTaken && !emailTaken);
  }

  @PostMapping("/api/auth/register") ResponseEntity<?> register(@RequestBody Register r) {
    if (r.handle()==null || !r.handle().matches("[a-zA-Z0-9_]{3,40}") || r.email()==null || !r.email().contains("@") || r.password()==null || r.password().length()<8)
      return ResponseEntity.badRequest().body(Map.of("error", "Use a 3-40 character handle, valid email, and 8+ character password."));
    try {
      db.update("insert into creators(handle,display_name,email,phone,password_hash,bio) values(?,?,?,?,?,?)", r.handle().toLowerCase(), r.displayName(), r.email().toLowerCase(), r.phone(), passwords.encode(r.password()), "");
      long id = db.queryForObject("select id from creators where handle=?", Long.class, r.handle().toLowerCase());
      db.update("insert into stores(creator_id,title) values(?,?)", id, r.displayName()+"'s Store");
      db.update("insert into notification_preferences(creator_id) values(?)", id);
      return ResponseEntity.status(201).body(Map.of("id", id, "handle", r.handle().toLowerCase(), "onboarding_next", "/subscribe/socials"));
    } catch (DataIntegrityViolationException e) { return ResponseEntity.status(409).body(Map.of("error", "Handle or email already exists.")); }
  }

  @GetMapping("/api/v1/users/get_user") Map<String,Object> getUser(@RequestParam(defaultValue="alex") String handle) {
    return first("select id,handle as username,display_name,email,phone,bio,avatar_url,created_at from creators where handle=?", handle);
  }

  @PostMapping("/api/v1/users/experiments/join_communities") Map<String,Object> joinCommunities() {
    return Map.of("joined", true, "community", "creator-foundations", "joined_at", Instant.now().toString());
  }

  @GetMapping("/api/v1/users/experiments/community_stats") Map<String,Object> communityStats() {
    return Map.of("members", 1248, "online", 73, "posts_this_week", 186);
  }

  @GetMapping("/api/v1/users/experiments/metadata") Map<String,Object> experimentMetadata() {
    return Map.of("flags", Map.of("community", true, "funnels", true, "appointments", true, "email_flows", true, "autodm", true), "variant", "control");
  }

  @GetMapping("/api/v1/integrations") List<Map<String,Object>> integrations(@RequestParam(defaultValue="1") long creatorId) {
    return db.queryForList("select id,provider,status,external_account_label from integrations where creator_id=? order by provider", creatorId);
  }

  @PutMapping("/api/v1/experiments/variant-assignment") Map<String,Object> variant(@RequestBody Map<String,Object> body) {
    return Map.of("experiment", text(body.get("experiment")), "variant", text(body.getOrDefault("variant", "control")), "saved", true);
  }

  @PutMapping("/api/v1/tags") ResponseEntity<?> upsertTag(@RequestBody Map<String,Object> body) {
    long creatorId = longValue(body.getOrDefault("creator_id", 1)); String name = text(body.get("name")).trim();
    if (name.isBlank()) return ResponseEntity.badRequest().body(Map.of("error", "name is required"));
    db.update("insert into tags(creator_id,name) values(?,?) on conflict(creator_id,name) do nothing", creatorId, name);
    return ResponseEntity.ok(first("select id,name from tags where creator_id=? and name=?", creatorId, name));
  }

  @GetMapping("/api/v1/dashboard") Map<String,Object> dashboard(@RequestParam(defaultValue="1") long creatorId) {
    Map<String,Object> store = first("select title,published,payouts_enabled from stores where creator_id=?", creatorId);
    return Map.of("store", store, "metrics", metrics(creatorId), "checklist", List.of(
        Map.of("id","profile","label","Complete your profile","complete",true),
        Map.of("id","product","label","Add a product","complete",count("select count(*) from products where creator_id=?", creatorId)>0),
        Map.of("id","payouts","label","Enable payouts","complete",Boolean.TRUE.equals(store.get("payouts_enabled")))));
  }

  @GetMapping("/api/v1/store") Map<String,Object> store(@RequestParam(defaultValue="1") long creatorId) {
    return Map.of("store", first("select id,title,theme,currency,published,payouts_enabled from stores where creator_id=?", creatorId),
        "products", db.queryForList("select id,type,title,description,price_cents,status,position,thumbnail_url from products where creator_id=? order by position,id", creatorId),
        "product_types", List.of("lead-magnet","digital-download","meeting","fulfillment","course","membership","webinar","community"));
  }

  @PostMapping("/api/v1/products") ResponseEntity<?> addProduct(@RequestBody ProductIn x) {
    if (!Set.of("lead-magnet","digital-download","meeting","fulfillment","course","membership","webinar","community").contains(x.type()))
      return ResponseEntity.badRequest().body(Map.of("error", "unsupported product type"));
    db.update("insert into products(creator_id,type,title,description,price_cents,status,position) values(?,?,?,?,?,?,?)", x.creatorId(), x.type(), x.title(), x.description(), x.priceCents(), x.status()==null?"draft":x.status(), x.position());
    long id = db.queryForObject("select max(id) from products where creator_id=?", Long.class, x.creatorId());
    return ResponseEntity.status(201).body(first("select id,type,title,description,price_cents,status,position from products where id=?", id));
  }

  @GetMapping("/api/v1/income") Map<String,Object> income(@RequestParam(defaultValue="1") long creatorId) {
    return Map.of("summary", first("select coalesce(sum(amount_cents),0) as gross_cents,coalesce(sum(fee_cents),0) as fees_cents,coalesce(sum(amount_cents-fee_cents),0) as net_cents,count(*) as order_count from orders where creator_id=? and status='paid'", creatorId),
        "orders", db.queryForList("select o.id,o.amount_cents,o.fee_cents,o.status,o.created_at,c.name as customer,p.title as product from orders o left join customers c on c.id=o.customer_id left join products p on p.id=o.product_id where o.creator_id=? order by o.created_at desc limit 100", creatorId),
        "currency", "USD", "cashout", Map.of("available_cents", 1710, "pending_cents", 0));
  }

  @GetMapping("/api/v1/analytics") Map<String,Object> analytics(@RequestParam(defaultValue="1") long creatorId) {
    return Map.of("totals", metrics(creatorId), "sources", db.queryForList("select coalesce(referrer,'direct') as source,count(*) as visits from store_visits where creator_id=? group by coalesce(referrer,'direct') order by visits desc", creatorId));
  }

  @GetMapping("/api/v1/customers") Map<String,Object> customers(@RequestParam(defaultValue="1") long creatorId) {
    return Map.of("items", db.queryForList("select id,name,email,phone,source,created_at from customers where creator_id=? order by created_at desc limit 5000", creatorId), "limit", 5000);
  }

  @PostMapping("/api/v1/customers") ResponseEntity<?> addCustomer(@RequestBody CustomerIn x) {
    try { db.update("insert into customers(creator_id,name,email,phone,source) values(?,?,?,?,?)", x.creatorId(),x.name(),x.email().toLowerCase(),x.phone(),"manual");
      return ResponseEntity.status(201).body(first("select id,name,email,phone,source,created_at from customers where creator_id=? and email=?", x.creatorId(),x.email().toLowerCase()));
    } catch (DataIntegrityViolationException e) { return ResponseEntity.status(409).body(Map.of("error","customer already exists")); }
  }

  @GetMapping("/api/v1/success") Map<String,Object> success() {
    return Map.of("tutorials", List.of(
        Map.of("id","launch","title","Launch your first offer","minutes",8,"category","Getting started"),
        Map.of("id","audience","title","Turn an audience into customers","minutes",12,"category","Growth"),
        Map.of("id","pricing","title","Price a digital product","minutes",10,"category","Sales")));
  }

  @GetMapping("/api/v1/more") Map<String,Object> more(@RequestParam(defaultValue="1") long creatorId) {
    return Map.of("funnels", db.queryForList("select id,name,status from funnels where creator_id=? order by id", creatorId),
        "appointments", db.queryForList("select b.id,b.starts_at,b.ends_at,b.status from bookings b join availability_schedules s on s.id=b.schedule_id where s.creator_id=? order by b.starts_at", creatorId),
        "features", List.of("funnels","appointments","referrals","email-flows","autodm"));
  }

  @GetMapping("/api/v1/settings") Map<String,Object> settings(@RequestParam(defaultValue="1") long creatorId) {
    return Map.of("profile", first("select id,handle as username,display_name,email,phone,bio,avatar_url from creators where id=?", creatorId),
        "store", first("select title,theme,currency,published,payouts_enabled from stores where creator_id=?", creatorId),
        "notifications", first("select order_emails,marketing_emails,payout_emails from notification_preferences where creator_id=?", creatorId),
        "tabs", List.of("profile","integrations","billing","payments","email-notifications","security"));
  }

  @GetMapping("/api/v1/automations/instagram-posts-metadata") Map<String,Object> instagramMetadata(@RequestParam(defaultValue="1") long creatorId) {
    return Map.of("connected", false, "posts", List.of(), "creator_id", creatorId);
  }

  @GetMapping("/api/v1/automations/analytics") Map<String,Object> automationAnalytics(@RequestParam(name="automation_ids", required=false) List<Long> ids) {
    if (ids==null || ids.isEmpty()) return Map.of("items", List.of());
    List<Map<String,Object>> items = new ArrayList<>();
    for (Long id : ids) {
      List<Map<String,Object>> rows = db.queryForList("select automation_id,comments_seen,messages_sent,link_clicks,updated_at from automation_stats where automation_id=?", id);
      if (rows.isEmpty()) items.add(Map.of("automation_id", id, "comments_seen", 0, "messages_sent", 0, "link_clicks", 0));
      else items.add(rows.get(0));
    }
    return Map.of("items", items);
  }

  @PostMapping("/events") ResponseEntity<?> analyticsEvent(@RequestBody Map<String,Object> event) {
    long creatorId = longValue(event.getOrDefault("creator_id", 1)); String name = text(event.getOrDefault("event", "page_view"));
    if (name.equals("page_view")) db.update("insert into store_visits(creator_id,path,referrer) values(?,?,?)", creatorId, text(event.getOrDefault("path", "/")), text(event.get("referrer")));
    return ResponseEntity.accepted().body(Map.of("accepted", true));
  }

  @PostMapping("/api/events/click") ResponseEntity<?> click(@RequestBody ClickIn x) {
    db.update("insert into click_events(link_id,referrer) values(?,?)", x.linkId(), x.referrer()); return ResponseEntity.accepted().build();
  }

  private Map<String,Object> metrics(long creatorId) {
    return Map.of("visits", count("select count(*) from store_visits where creator_id=?", creatorId),
        "leads", count("select count(*) from leads where creator_id=?", creatorId),
        "orders", count("select count(*) from orders where creator_id=? and status='paid'", creatorId),
        "revenue_cents", count("select coalesce(sum(amount_cents),0) from orders where creator_id=? and status='paid'", creatorId));
  }
  private Map<String,Object> first(String sql, Object... args) { List<Map<String,Object>> rows=db.queryForList(sql,args); return rows.isEmpty()?Map.of():rows.get(0); }
  private long count(String sql,Object... args) { Number n=db.queryForObject(sql,Number.class,args); return n==null?0:n.longValue(); }
  private static long number(Object x) { return ((Number)x).longValue(); }
  private static long longValue(Object x) { return x instanceof Number n?n.longValue():Long.parseLong(text(x)); }
  private static String text(Object x) { return x==null?"":String.valueOf(x); }

  record Register(String handle,String displayName,String email,String phone,String password) {}
  record ProductIn(long creatorId,String type,String title,String description,int priceCents,String status,int position) {}
  record CustomerIn(long creatorId,String name,String email,String phone) {}
  record ClickIn(long linkId,String referrer) {}
}
