package dev.creatorstore;

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
    if (db.queryForObject("select count(*) from creators", Integer.class) == 0) {
      db.update("insert into creators(handle,display_name,email,password_hash,bio) values(?,?,?,?,?)", "alex", "Alex Creator", "alex@example.test", "demo-not-a-real-password", "Small tools for ambitious creators.");
      Long id=db.queryForObject("select id from creators where handle='alex'", Long.class);
      db.update("insert into links(creator_id,title,url,position) values(?,?,?,?)",id,"My weekly newsletter","https://example.com/newsletter",1);
      db.update("insert into products(creator_id,title,description,price_cents) values(?,?,?,?)",id,"Creator Content Calendar","A practical 30-day Notion-style content plan.",1900);
    }}; }
}

@RestController @CrossOrigin(origins = "http://localhost:5173")
class CreatorController {
  private final JdbcTemplate db;
  private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder();
  CreatorController(JdbcTemplate db) { this.db=db; }
  @GetMapping("/health") Map<String,String> health() { return Map.of("status","ok"); }
  @GetMapping("/api/public/{handle}") ResponseEntity<?> publicPage(@PathVariable String handle) {
    List<Map<String,Object>> creators=db.queryForList("select id,handle,display_name,bio from creators where handle=?",handle.toLowerCase());
    if(creators.isEmpty()) return ResponseEntity.notFound().build(); Map<String,Object> c=creators.get(0); Long id=((Number)c.get("id")).longValue();
    return ResponseEntity.ok(Map.of("creator",c,"links",db.queryForList("select id,title,url from links where creator_id=? order by position,id",id),"products",db.queryForList("select id,title,description,price_cents from products where creator_id=? order by id",id)));
  }
  @PostMapping("/api/auth/register") ResponseEntity<?> register(@RequestBody Register r) {
    if(r.handle()==null || !r.handle().matches("[a-zA-Z0-9_]{3,40}") || r.email()==null || !r.email().contains("@") || r.password()==null || r.password().length()<8) return ResponseEntity.badRequest().body(Map.of("error","Use a 3-40 character handle, valid email, and 8+ character password."));
    try { db.update("insert into creators(handle,display_name,email,password_hash,bio) values(?,?,?,?,?)",r.handle().toLowerCase(),r.displayName(),r.email().toLowerCase(),passwords.encode(r.password()),""); Long id=db.queryForObject("select id from creators where handle=?",Long.class,r.handle().toLowerCase()); return ResponseEntity.status(201).body(Map.of("id",id,"handle",r.handle().toLowerCase())); }
    catch(DataIntegrityViolationException e) { return ResponseEntity.status(409).body(Map.of("error","Handle or email already exists.")); }
  }
  @PostMapping("/api/creators/{id}/links") ResponseEntity<?> addLink(@PathVariable long id,@RequestBody LinkIn x) { db.update("insert into links(creator_id,title,url,position) values(?,?,?,?)",id,x.title(),x.url(),x.position()); return ResponseEntity.status(201).build(); }
  @PostMapping("/api/creators/{id}/products") ResponseEntity<?> addProduct(@PathVariable long id,@RequestBody ProductIn x) { db.update("insert into products(creator_id,title,description,price_cents) values(?,?,?,?)",id,x.title(),x.description(),x.priceCents()); return ResponseEntity.status(201).build(); }
  @PostMapping("/api/events/click") ResponseEntity<?> click(@RequestBody ClickIn x) { db.update("insert into click_events(link_id,referrer) values(?,?)",x.linkId(),x.referrer()); return ResponseEntity.accepted().build(); }
  record Register(String handle,String displayName,String email,String password){} record LinkIn(String title,String url,int position){} record ProductIn(String title,String description,int priceCents){} record ClickIn(long linkId,String referrer){}
}
