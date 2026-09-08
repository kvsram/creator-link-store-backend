package dev.creatorstore.repository;

import dev.creatorstore.domain.ProductTypeConfiguration;
import dev.creatorstore.domain.ProductTypeConfiguration.CheckoutField;
import dev.creatorstore.domain.ProductTypeConfiguration.Course;
import dev.creatorstore.domain.ProductTypeConfiguration.CourseLesson;
import dev.creatorstore.domain.ProductTypeConfiguration.CourseModule;
import dev.creatorstore.domain.ProductTypeConfiguration.Meeting;
import dev.creatorstore.domain.ProductTypeConfiguration.Membership;
import dev.creatorstore.domain.ProductTypeConfiguration.PaymentPlan;
import dev.creatorstore.domain.ProductTypeConfiguration.Slot;
import dev.creatorstore.domain.ProductTypeConfiguration.Webinar;
import dev.creatorstore.domain.ProductTypeConfiguration.WebinarSession;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

/** Persistence adapter for the operational tables derived from the editor configuration. */
@Repository
public class ProductConfigurationRepository {
  private final JdbcTemplate database;

  public ProductConfigurationRepository(JdbcTemplate database) {
    this.database = database;
  }

  public ProductTypeConfiguration synchronize(long creatorId, long productId,
      ProductTypeConfiguration configuration) {
    if (configuration instanceof Meeting value) return synchronizeMeeting(creatorId, productId, value);
    if (configuration instanceof Webinar value) return synchronizeWebinar(productId, value);
    if (configuration instanceof Course value) return synchronizeCourse(productId, value);
    if (configuration instanceof Membership value) return synchronizeMembership(productId, value);
    if (configuration instanceof ProductTypeConfiguration.Fulfillment value)
      return synchronizeCheckoutFields(productId, value);
    return configuration;
  }

  private Meeting synchronizeMeeting(long creatorId, long productId, Meeting value) {
    List<Map<String, Object>> schedules = database.queryForList(
        "select id from availability_schedules where product_id=?", productId);
    long scheduleId;
    if (schedules.isEmpty()) {
      scheduleId = database.queryForObject(
          "insert into availability_schedules(creator_id,product_id,name,timezone) values(?,?,?,?) returning id",
          Long.class, creatorId, productId, "Product " + productId + " availability", value.timezone());
    } else {
      scheduleId = number(schedules.get(0).get("id"));
      database.update("update availability_schedules set timezone=? where id=? and creator_id=?",
          value.timezone(), scheduleId, creatorId);
    }

    Set<Long> existing = ids(database.queryForList(
        "select id from bookings where product_id=?", productId));
    Set<Long> retained = new HashSet<>();
    List<Slot> saved = new ArrayList<>();
    for (Slot slot : value.slots()) {
      Long id = slot.id();
      if (id != null && !existing.contains(id))
        throw badRequest("meeting slot does not belong to this product");
      if (id != null && existing.contains(id)) {
        int changed = database.update(
            "update bookings set schedule_id=?,starts_at=?,ends_at=? where id=? and product_id=? and status='open' and customer_id is null "
                + "and not exists(select 1 from checkout_sessions c where c.slot_id=bookings.id)",
            scheduleId, Timestamp.from(slot.startsAt()), Timestamp.from(slot.endsAt()), id, productId);
        if (changed == 0) {
          Map<String, Object> locked = database.queryForMap(
              "select starts_at,ends_at from bookings where id=? and product_id=?", id, productId);
          Instant lockedStart = instant(locked.get("starts_at"));
          Instant lockedEnd = instant(locked.get("ends_at"));
          if (!lockedStart.equals(slot.startsAt()) || !lockedEnd.equals(slot.endsAt()))
            throw conflict("a reserved meeting slot cannot be changed; add a new slot instead");
          saved.add(new Slot(id, lockedStart, lockedEnd));
        } else saved.add(slot);
      } else {
        id = database.queryForObject(
            "insert into bookings(schedule_id,product_id,starts_at,ends_at,status) values(?,?,?,?, 'open') returning id",
            Long.class, scheduleId, productId, Timestamp.from(slot.startsAt()), Timestamp.from(slot.endsAt()));
        saved.add(new Slot(id, slot.startsAt(), slot.endsAt()));
      }
      retained.add(id);
    }
    deleteMissingOpenSlots(productId, retained);
    return new Meeting(value.schemaVersion(), value.location(), value.locationDetails(),
        value.timezone(), value.durationMinutes(), value.maxAttendees(), value.minNoticeHours(),
        value.bufferMinutes(), List.copyOf(saved));
  }

  private void deleteMissingOpenSlots(long productId, Set<Long> retained) {
    for (Map<String, Object> row : database.queryForList(
        "select id from bookings where product_id=? and status='open' and customer_id is null "
            + "and not exists(select 1 from checkout_sessions c where c.slot_id=bookings.id)", productId)) {
      long id = number(row.get("id"));
      if (!retained.contains(id)) database.update("delete from bookings where id=?", id);
    }
  }

  private Webinar synchronizeWebinar(long productId, Webinar value) {
    Set<Long> existing = ids(database.queryForList(
        "select id from webinar_sessions where product_id=?", productId));
    Set<Long> retained = new HashSet<>();
    List<WebinarSession> saved = new ArrayList<>();
    for (WebinarSession session : value.sessions()) {
      Long id = session.id();
      if (id != null && !existing.contains(id))
        throw badRequest("webinar session does not belong to this product");
      if (id != null && existing.contains(id)) {
        if (isWebinarRegistered(id)) {
          Map<String, Object> locked = database.queryForMap(
              "select starts_at,ends_at,join_url,capacity from webinar_sessions where id=?", id);
          boolean unchanged = instant(locked.get("starts_at")).equals(session.startsAt())
              && instant(locked.get("ends_at")).equals(session.endsAt())
              && String.valueOf(locked.get("join_url")).equals(session.joinUrl())
              && ((Number) locked.get("capacity")).intValue() == session.capacity();
          if (!unchanged)
            throw conflict("a webinar session with registrations cannot be changed; add a new session instead");
        }
        if (id != null) database.update(
            "update webinar_sessions set starts_at=?,ends_at=?,join_url=?,capacity=? where id=? and product_id=?",
            Timestamp.from(session.startsAt()), Timestamp.from(session.endsAt()), session.joinUrl(),
            session.capacity(), id, productId);
      }
      if (id == null || !existing.contains(id)) {
        id = database.queryForObject(
            "insert into webinar_sessions(product_id,starts_at,ends_at,join_url,capacity) values(?,?,?,?,?) returning id",
            Long.class, productId, Timestamp.from(session.startsAt()), Timestamp.from(session.endsAt()),
            session.joinUrl(), session.capacity());
      }
      retained.add(id);
      saved.add(new WebinarSession(id, session.startsAt(), session.endsAt(), session.capacity(),
          session.joinUrl()));
    }
    for (Long id : existing)
      if (!retained.contains(id) && isWebinarRegistered(id))
        throw conflict("a webinar session with registrations cannot be removed");
    for (Map<String, Object> row : database.queryForList(
        "select s.id from webinar_sessions s where s.product_id=? and not exists "
            + "(select 1 from webinar_registrations r where r.session_id=s.id)", productId)) {
      long id = number(row.get("id"));
      if (!retained.contains(id)) database.update("delete from webinar_sessions where id=?", id);
    }
    return new Webinar(value.schemaVersion(), value.location(), value.timezone(),
        value.durationMinutes(), value.capacity(), List.copyOf(saved));
  }

  private Course synchronizeCourse(long productId, Course value) {
    List<Map<String, Object>> currentModules = database.queryForList(
        "select id,position from course_modules where product_id=? order by position,id", productId);
    Set<Long> existingIds = ids(currentModules);
    Set<Long> retainedModules = new HashSet<>();
    List<CourseModule> savedModules = new ArrayList<>();
    for (int moduleIndex = 0; moduleIndex < value.modules().size(); moduleIndex++) {
      CourseModule module = value.modules().get(moduleIndex);
      if (module.id() != null && !existingIds.contains(module.id()))
        throw badRequest("course module does not belong to this product");
      Long moduleId = ownedOrPositional(module.id(), existingIds, currentModules, moduleIndex);
      if (moduleId == null) {
        moduleId = database.queryForObject(
            "insert into course_modules(product_id,title,description,position) values(?,?,?,?) returning id",
            Long.class, productId, module.title(), module.description(), module.position());
      } else {
        database.update(
            "update course_modules set title=?,description=?,position=? where id=? and product_id=?",
            module.title(), module.description(), module.position(), moduleId, productId);
      }
      retainedModules.add(moduleId);
      List<CourseLesson> savedLessons = synchronizeLessons(moduleId, module.lessons());
      savedModules.add(new CourseModule(moduleId, module.title(), module.description(),
          module.position(), savedLessons));
    }
    for (Map<String, Object> row : currentModules) {
      long id = number(row.get("id"));
      if (!retainedModules.contains(id)) {
        if (hasProgressedModule(id))
          throw conflict("a course module with learner progress cannot be removed");
        database.update("delete from course_modules where id=?", id);
      }
    }
    return new Course(value.schemaVersion(), value.dripDays(), List.copyOf(savedModules));
  }

  private List<CourseLesson> synchronizeLessons(long moduleId, List<CourseLesson> lessons) {
    List<Map<String, Object>> current = database.queryForList(
        "select id,position from course_lessons where module_id=? order by position,id", moduleId);
    Set<Long> existing = ids(current);
    Set<Long> retained = new HashSet<>();
    List<CourseLesson> saved = new ArrayList<>();
    for (int index = 0; index < lessons.size(); index++) {
      CourseLesson lesson = lessons.get(index);
      if (lesson.id() != null && !existing.contains(lesson.id()))
        throw badRequest("course lesson does not belong to this module");
      Long id = ownedOrPositional(lesson.id(), existing, current, index);
      if (id == null) {
        id = database.queryForObject(
            "insert into course_lessons(module_id,title,video_url,content,position) values(?,?,?,?,?) returning id",
            Long.class, moduleId, lesson.title(), emptyToNull(lesson.videoUrl()),
            lesson.description(), lesson.position());
      } else {
        database.update(
            "update course_lessons set title=?,video_url=?,content=?,position=? where id=? and module_id=?",
            lesson.title(), emptyToNull(lesson.videoUrl()), lesson.description(), lesson.position(),
            id, moduleId);
      }
      retained.add(id);
      saved.add(new CourseLesson(id, lesson.title(), lesson.videoUrl(), lesson.description(),
          lesson.position()));
    }
    for (Map<String, Object> row : current) {
      long id = number(row.get("id"));
      if (!retained.contains(id)) {
        if (hasLessonProgress(id))
          throw conflict("a course lesson with learner progress cannot be removed");
        database.update("delete from course_lessons where id=?", id);
      }
    }
    return List.copyOf(saved);
  }

  private Membership synchronizeMembership(long productId, Membership value) {
    List<Map<String, Object>> current = database.queryForList(
        "select id,name,amount_cents,interval_name,interval_count from product_payment_plans where product_id=? order by id", productId);
    Set<Long> existing = ids(current);
    Set<Long> retained = new HashSet<>();
    List<PaymentPlan> saved = new ArrayList<>();
    for (PaymentPlan plan : value.plans()) {
      Long id = plan.id();
      if (id != null && !existing.contains(id))
        throw badRequest("membership plan does not belong to this product");
      if (id == null) id = idByName(current, plan.name());
      String dbInterval = databaseInterval(plan.interval());
      if (id != null && isPlanReferenced(id)) {
        Map<String, Object> old = rowById(current, id);
        boolean unchanged = old != null
            && plan.name().equals(String.valueOf(old.get("name")))
            && plan.amountSubunits() == ((Number) old.get("amount_cents")).intValue()
            && dbInterval.equals(String.valueOf(old.get("interval_name")))
            && plan.intervalCount() == ((Number) old.get("interval_count")).intValue();
        if (!unchanged)
          throw conflict("a membership plan used by checkout or a subscription cannot be changed; add a new plan instead");
      }
      if (id == null) {
        id = database.queryForObject(
            "insert into product_payment_plans(product_id,name,amount_cents,interval_name,interval_count) values(?,?,?,?,?) returning id",
            Long.class, productId, plan.name(), plan.amountSubunits(), dbInterval,
            plan.intervalCount());
      } else {
        database.update(
            "update product_payment_plans set name=?,amount_cents=?,interval_name=?,interval_count=? where id=? and product_id=?",
            plan.name(), plan.amountSubunits(), dbInterval, plan.intervalCount(), id, productId);
      }
      retained.add(id);
      saved.add(new PaymentPlan(id, plan.name(), plan.amountSubunits(), plan.interval(),
          plan.intervalCount()));
    }
    for (Map<String, Object> row : current) {
      long id = number(row.get("id"));
      if (!retained.contains(id)) {
        if (isPlanReferenced(id))
          throw conflict("a membership plan used by checkout or a subscription cannot be removed");
        database.update("delete from product_payment_plans where id=?", id);
      }
    }
    return new Membership(value.schemaVersion(), value.benefits(), value.memberBenefits(),
        value.welcomeMessage(), List.copyOf(saved));
  }

  private ProductTypeConfiguration.Fulfillment synchronizeCheckoutFields(long productId,
      ProductTypeConfiguration.Fulfillment value) {
    List<Map<String, Object>> current = database.queryForList(
        "select id,label,field_type,required,position from product_checkout_fields where product_id=? order by position,id", productId);
    Set<Long> existing = ids(current);
    Set<Long> retained = new HashSet<>();
    List<CheckoutField> saved = new ArrayList<>();
    for (CheckoutField field : value.checkoutFields()) {
      Long id = field.id();
      if (id != null && !existing.contains(id))
        throw badRequest("checkout field does not belong to this product");
      if (id == null) id = idByName(current, field.label());
      if (id != null && isCheckoutFieldReferenced(id)) {
        Map<String, Object> old = rowById(current, id);
        boolean unchanged = old != null
            && field.label().equals(String.valueOf(old.get("label")))
            && field.fieldType().equals(String.valueOf(old.get("field_type")))
            && field.required() == Boolean.TRUE.equals(old.get("required"))
            && field.position() == ((Number) old.get("position")).intValue();
        if (!unchanged)
          throw conflict("a checkout field with order responses cannot be changed; add a new field instead");
      }
      if (id == null) {
        id = database.queryForObject(
            "insert into product_checkout_fields(product_id,label,field_type,required,position) values(?,?,?,?,?) returning id",
            Long.class, productId, field.label(), field.fieldType(), field.required(),
            field.position());
      } else {
        database.update(
            "update product_checkout_fields set label=?,field_type=?,required=?,position=? where id=? and product_id=?",
            field.label(), field.fieldType(), field.required(), field.position(), id, productId);
      }
      retained.add(id);
      saved.add(new CheckoutField(id, field.label(), field.fieldType(), field.required(),
          field.position()));
    }
    for (Map<String, Object> row : current) {
      long id = number(row.get("id"));
      if (!retained.contains(id)) {
        if (isCheckoutFieldReferenced(id))
          throw conflict("a checkout field with order responses cannot be removed");
        database.update("delete from product_checkout_fields where id=?", id);
      }
    }
    return new ProductTypeConfiguration.Fulfillment(value.schemaVersion(), value.turnaroundDays(),
        value.deliveryFormat(), value.buyerInstructions(), List.copyOf(saved));
  }

  public List<Map<String, Object>> meetingSlots(long productId, boolean publicOnly) {
    String filter = publicOnly ? " and b.status='open' and b.starts_at>current_timestamp" : "";
    return database.queryForList(
        "select b.id,b.starts_at,b.ends_at,b.status from bookings b where b.product_id=?" + filter
            + " order by b.starts_at,b.id", productId);
  }

  public List<Map<String, Object>> webinarSessions(long productId, boolean includePrivate) {
    String columns = includePrivate
        ? "s.id,s.starts_at,s.ends_at,s.capacity,s.join_url"
        : "s.id,s.starts_at,s.ends_at,s.capacity";
    String filter = includePrivate ? "" : " and s.ends_at>current_timestamp";
    return database.queryForList("select " + columns
        + " from webinar_sessions s where s.product_id=?" + filter + " order by s.starts_at,s.id",
        productId);
  }

  public List<Map<String, Object>> paymentPlans(long productId) {
    List<Map<String, Object>> rows = database.queryForList(
        "select id,name,amount_cents as amount_subunits,interval_name,interval_count from product_payment_plans where product_id=? order by id",
        productId);
    rows.forEach(row -> row.put("interval", clientInterval(String.valueOf(row.remove("interval_name")))));
    return rows;
  }

  public List<Map<String, Object>> checkoutFields(long productId) {
    return database.queryForList(
        "select id,label,field_type,required,position from product_checkout_fields where product_id=? order by position,id",
        productId);
  }

  public List<Map<String, Object>> courseModules(long productId, boolean includePrivate) {
    String moduleColumns = includePrivate
        ? "id,title,description,position"
        : "id,title,position";
    List<Map<String, Object>> modules = database.queryForList(
        "select " + moduleColumns + " from course_modules where product_id=? order by position,id",
        productId);
    for (Map<String, Object> module : modules) {
      String columns = includePrivate
          ? "id,title,video_url,content as description,position"
          : "id,title,position";
      module.put("lessons", database.queryForList("select " + columns
          + " from course_lessons where module_id=? order by position,id", module.get("id")));
    }
    return modules;
  }

  public long futureMeetingSlotCount(long productId) {
    return count("select count(*) from bookings where product_id=? and status='open' and starts_at>current_timestamp",
        productId);
  }

  public long futureWebinarSessionCount(long productId) {
    return count("select count(*) from webinar_sessions where product_id=? and ends_at>current_timestamp and join_url<>''",
        productId);
  }

  public long courseLessonCount(long productId) {
    return count("select count(*) from course_lessons l join course_modules m on m.id=l.module_id where m.product_id=?",
        productId);
  }

  public long membershipPlanCount(long productId) {
    return count("select count(*) from product_payment_plans where product_id=? and amount_cents>0",
        productId);
  }

  public long checkoutFieldCount(long productId) {
    return count("select count(*) from product_checkout_fields where product_id=?", productId);
  }

  private long count(String sql, long productId) {
    Number result = database.queryForObject(sql, Number.class, productId);
    return result == null ? 0 : result.longValue();
  }

  private static Set<Long> ids(List<Map<String, Object>> rows) {
    Set<Long> ids = new HashSet<>();
    rows.forEach(row -> ids.add(number(row.get("id"))));
    return ids;
  }

  private static Long ownedOrPositional(Long requested, Set<Long> existing,
      List<Map<String, Object>> ordered, int index) {
    if (requested != null && existing.contains(requested)) return requested;
    return requested == null && index < ordered.size() ? number(ordered.get(index).get("id")) : null;
  }

  private static Long idByName(List<Map<String, Object>> rows, String name) {
    for (Map<String, Object> row : rows) {
      Object candidate = row.containsKey("name") ? row.get("name") : row.get("label");
      if (name.equals(candidate)) return number(row.get("id"));
    }
    return null;
  }

  private boolean isWebinarRegistered(long sessionId) {
    return count("select count(*) from webinar_registrations where session_id=?", sessionId) > 0;
  }

  private boolean isPlanReferenced(long planId) {
    Number value = database.queryForObject(
        "select (select count(*) from checkout_sessions where plan_id=?) + "
            + "(select count(*) from membership_subscriptions where plan_id=?)",
        Number.class, planId, planId);
    return value != null && value.longValue() > 0;
  }

  private boolean hasProgressedModule(long moduleId) {
    return count("select count(*) from course_lessons l join lesson_progress p on p.lesson_id=l.id where l.module_id=?",
        moduleId) > 0;
  }

  private boolean hasLessonProgress(long lessonId) {
    return count("select count(*) from lesson_progress where lesson_id=?", lessonId) > 0;
  }

  private boolean isCheckoutFieldReferenced(long fieldId) {
    return count("select count(*) from order_field_responses where field_id=?", fieldId) > 0;
  }

  private static Map<String, Object> rowById(List<Map<String, Object>> rows, long id) {
    for (Map<String, Object> row : rows)
      if (number(row.get("id")) == id) return row;
    return null;
  }

  private static long number(Object value) { return ((Number) value).longValue(); }

  private static String emptyToNull(String value) { return value == null || value.isBlank() ? null : value; }

  private static Instant instant(Object value) {
    if (value instanceof Timestamp timestamp) return timestamp.toInstant();
    if (value instanceof OffsetDateTime dateTime) return dateTime.toInstant();
    if (value instanceof Instant instant) return instant;
    return Instant.parse(String.valueOf(value));
  }

  private static String databaseInterval(String interval) {
    return switch (interval) {
      case "daily" -> "day";
      case "weekly" -> "week";
      case "monthly" -> "month";
      case "annual" -> "year";
      default -> throw new IllegalArgumentException("unsupported interval");
    };
  }

  private static String clientInterval(String interval) {
    return switch (interval) {
      case "day" -> "daily";
      case "week" -> "weekly";
      case "month" -> "monthly";
      case "year" -> "annual";
      default -> interval;
    };
  }

  private static ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }
}
