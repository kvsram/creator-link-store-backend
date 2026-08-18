package dev.creatorstore.bootstrap;

import java.util.List;
import java.util.UUID;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class DemoDataInitializer implements CommandLineRunner {
  private final JdbcTemplate database;
  private final BCryptPasswordEncoder passwordEncoder;

  public DemoDataInitializer(JdbcTemplate database, BCryptPasswordEncoder passwordEncoder) {
    this.database = database;
    this.passwordEncoder = passwordEncoder;
  }

  @Override
  public void run(String... args) {
    if (database.queryForObject("select count(*) from creators", Integer.class) != 0) return;
    String disabledCredential = passwordEncoder.encode(UUID.randomUUID().toString());
    database.update("insert into creators(handle,display_name,email,password_hash,bio) values(?,?,?,?,?)",
        "alex", "Alex Rivera", "alex@example.test", disabledCredential,
        "Systems and templates for independent creators.");
    long creatorId = database.queryForObject("select id from creators where handle='alex'", Long.class);
    database.update("insert into stores(creator_id,title,currency,payouts_enabled) values(?,?,?,?)",
        creatorId, "Alex's Creator Store", "INR", false);
    database.update("insert into links(creator_id,title,url,position) values(?,?,?,?)",
        creatorId, "Free weekly newsletter", "https://example.com/newsletter", 1);
    database.update("insert into products(creator_id,type,title,description,price_cents,position) values(?,?,?,?,?,?)",
        creatorId, "digital-download", "Creator Content Calendar",
        "A practical 30-day content planning workbook.", 49900, 1);
    database.update("insert into products(creator_id,type,title,description,price_cents,position) values(?,?,?,?,?,?)",
        creatorId, "meeting", "Strategy Session", "A focused 45-minute planning call.", 249900, 2);
    long productId = database.queryForObject(
        "select id from products where creator_id=? order by id limit 1", Long.class, creatorId);
    database.update("insert into customers(creator_id,name,email,source) values(?,?,?,?)",
        creatorId, "Jamie Chen", "jamie@example.test", "checkout");
    long customerId = database.queryForObject(
        "select id from customers where creator_id=? limit 1", Long.class, creatorId);
    database.update("insert into orders(creator_id,customer_id,product_id,amount_cents,fee_cents,status,created_at) values(?,?,?,?,?,'paid',current_timestamp - interval '2 days')",
        creatorId, customerId, productId, 49900, 998);
    database.update("insert into leads(creator_id,product_id,email) values(?,?,?)",
        creatorId, productId, "reader@example.test");
    database.update("insert into store_visits(creator_id,path,referrer,occurred_at) values(?,?,?,current_timestamp - interval '1 day')",
        creatorId, "/alex", "instagram.com");
    for (String provider : List.of("razorpay", "stripe", "mailchimp", "zoom", "google-calendar", "instagram")) {
      database.update("insert into integrations(creator_id,provider,status) values(?,?,?)",
          creatorId, provider, "disconnected");
    }
    database.update("insert into notification_preferences(creator_id) values(?)", creatorId);
    database.update("insert into automations(creator_id,name,trigger_type,message,status) values(?,?,?,?,?)",
        creatorId, "Send free guide", "instagram_comment",
        "Thanks! Here is the guide: https://example.com/guide", "draft");
  }
}
