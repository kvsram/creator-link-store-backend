package dev.creatorstore.service;

import dev.creatorstore.domain.ProductTypeConfiguration;
import dev.creatorstore.domain.ProductTypeConfiguration.CheckoutField;
import dev.creatorstore.domain.ProductTypeConfiguration.Community;
import dev.creatorstore.domain.ProductTypeConfiguration.Course;
import dev.creatorstore.domain.ProductTypeConfiguration.CourseLesson;
import dev.creatorstore.domain.ProductTypeConfiguration.CourseModule;
import dev.creatorstore.domain.ProductTypeConfiguration.Delivery;
import dev.creatorstore.domain.ProductTypeConfiguration.Fulfillment;
import dev.creatorstore.domain.ProductTypeConfiguration.Meeting;
import dev.creatorstore.domain.ProductTypeConfiguration.Membership;
import dev.creatorstore.domain.ProductTypeConfiguration.PaymentPlan;
import dev.creatorstore.domain.ProductTypeConfiguration.Slot;
import dev.creatorstore.domain.ProductTypeConfiguration.Webinar;
import dev.creatorstore.domain.ProductTypeConfiguration.WebinarSession;
import java.net.URI;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Converts untrusted editor JSON into one of the eight canonical configuration models. */
@Component
public class ProductConfigurationValidator {
  public static final int SCHEMA_VERSION = 1;

  public ProductTypeConfiguration validate(String type, Map<String, Object> raw) {
    Map<String, Object> input = raw == null ? Map.of() : raw;
    schemaVersion(input);
    return switch (type) {
      case "digital-download" -> delivery(input, false);
      case "lead-magnet" -> delivery(input, true);
      case "meeting" -> meeting(input);
      case "webinar" -> webinar(input);
      case "course" -> course(input);
      case "membership" -> membership(input);
      case "fulfillment" -> fulfillment(input);
      case "community" -> community(input);
      default -> throw badRequest("unsupported product type");
    };
  }

  public Map<String, Object> editorSnapshot(ProductTypeConfiguration value) {
    Map<String, Object> output = new LinkedHashMap<>();
    output.put("schemaVersion", SCHEMA_VERSION);
    if (value instanceof Delivery c) {
      output.put("deliveryMode", c.deliveryMode());
      output.put("redirectUrl", c.redirectUrl());
      output.put("collectName", c.collectName());
      output.put("collectEmail", c.collectEmail());
      output.put("collectPhone", c.collectPhone());
      output.put("consentText", c.consentText());
    } else if (value instanceof Meeting c) {
      output.put("location", c.location());
      output.put("locationDetails", c.locationDetails());
      output.put("timezone", c.timezone());
      output.put("durationMinutes", c.durationMinutes());
      output.put("maxAttendees", c.maxAttendees());
      output.put("minNoticeHours", c.minNoticeHours());
      output.put("bufferMinutes", c.bufferMinutes());
      output.put("slots", c.slots().stream().map(this::slotMap).toList());
    } else if (value instanceof Webinar c) {
      output.put("location", c.location());
      output.put("timezone", c.timezone());
      output.put("durationMinutes", c.durationMinutes());
      output.put("capacity", c.capacity());
      output.put("sessions", c.sessions().stream().map(this::sessionMap).toList());
    } else if (value instanceof Course c) {
      output.put("dripDays", c.dripDays());
      output.put("modules", c.modules().stream().map(this::moduleMap).toList());
    } else if (value instanceof Membership c) {
      output.put("benefits", c.benefits());
      output.put("memberBenefits", c.memberBenefits());
      output.put("welcomeMessage", c.welcomeMessage());
      output.put("plans", c.plans().stream().map(this::planMap).toList());
    } else if (value instanceof Fulfillment c) {
      output.put("turnaroundDays", c.turnaroundDays());
      output.put("deliveryFormat", c.deliveryFormat());
      output.put("buyerInstructions", c.buyerInstructions());
      output.put("checkoutFields", c.checkoutFields().stream().map(this::fieldMap).toList());
    } else if (value instanceof Community c) {
      output.put("platform", c.platform());
      output.put("accessUrl", c.accessUrl());
      output.put("benefits", c.benefits());
      output.put("memberBenefits", c.memberBenefits());
      output.put("welcomeMessage", c.welcomeMessage());
    }
    return output;
  }

  private Delivery delivery(Map<String, Object> input, boolean leadMagnet) {
    // Delivery uses one canonical record for paid downloads and lead magnets. Accept the
    // canonical lead-capture keys on re-read for both types, then ignore them for paid products.
    rejectUnknown(input, Set.of("schemaVersion", "deliveryMode", "redirectUrl", "collectName",
        "collectEmail", "collectPhone", "consentText"));
    String mode = optionalText(input, "deliveryMode", "upload", 20);
    if (!Set.of("upload", "redirect").contains(mode))
      throw badRequest("deliveryMode must be upload or redirect");
    String redirect = optionalText(input, "redirectUrl", "", 2048);
    if (!redirect.isBlank()) requireHttps(redirect, "redirectUrl");
    boolean collectName = leadMagnet && optionalBoolean(input, "collectName", true);
    boolean collectEmail = !leadMagnet || optionalBoolean(input, "collectEmail", true);
    if (leadMagnet && !collectEmail) throw badRequest("lead magnets must collect email");
    boolean collectPhone = leadMagnet && optionalBoolean(input, "collectPhone", false);
    String consent = leadMagnet ? optionalText(input, "consentText", "", 500) : "";
    return new Delivery(SCHEMA_VERSION, mode, redirect, collectName, collectEmail,
        collectPhone, consent);
  }

  private Meeting meeting(Map<String, Object> input) {
    rejectUnknown(input, Set.of("schemaVersion", "location", "locationDetails", "timezone",
        "durationMinutes", "maxAttendees", "minNoticeHours", "bufferMinutes", "availability", "slots"));
    String location = optionalText(input, "location", "google-meet", 40);
    if (!Set.of("google-meet", "zoom", "phone", "custom", "in-person").contains(location))
      throw badRequest("unsupported meeting location");
    String locationDetails = optionalText(input, "locationDetails", "", 500);
    String timezone = optionalText(input, "timezone", "UTC", 80);
    try { ZoneId.of(timezone); }
    catch (DateTimeException exception) { throw badRequest("timezone must be a valid IANA time zone"); }
    int duration = integer(input, "durationMinutes", 60, 15, 480);
    int attendees = integer(input, "maxAttendees", 1, 1, 500);
    int notice = integer(input, "minNoticeHours", 0, 0, 8760);
    int buffer = integer(input, "bufferMinutes", 0, 0, 240);
    List<Slot> slots = slots(input.get("slots"), duration);
    return new Meeting(SCHEMA_VERSION, location, locationDetails, timezone, duration, attendees,
        notice, buffer, slots);
  }

  private Webinar webinar(Map<String, Object> input) {
    rejectUnknown(input, Set.of("schemaVersion", "location", "timezone", "durationMinutes",
        "capacity", "sessions", "startsAt", "endsAt", "joinUrl"));
    String location = optionalText(input, "location", "google-meet", 40);
    if (!Set.of("google-meet", "zoom", "custom").contains(location))
      throw badRequest("unsupported webinar location");
    String timezone = optionalText(input, "timezone", "UTC", 80);
    try { ZoneId.of(timezone); }
    catch (DateTimeException exception) { throw badRequest("timezone must be a valid IANA time zone"); }
    int duration = integer(input, "durationMinutes", 60, 15, 480);
    int capacity = integer(input, "capacity", 100, 1, 100_000);
    List<WebinarSession> sessions = webinarSessions(input.get("sessions"), capacity);
    if (sessions.isEmpty() && !optionalText(input, "startsAt", "", 50).isBlank()) {
      Instant starts = instant(input.get("startsAt"), "startsAt");
      String endText = optionalText(input, "endsAt", "", 50);
      Instant ends = endText.isBlank() ? starts.plus(Duration.ofMinutes(duration))
          : instant(endText, "endsAt");
      if (!ends.isAfter(starts)) throw badRequest("webinar endsAt must be after startsAt");
      String joinUrl = optionalText(input, "joinUrl", "", 2048);
      if (!joinUrl.isBlank()) requireHttps(joinUrl, "joinUrl");
      sessions = List.of(new WebinarSession(null, starts, ends, capacity, joinUrl));
    }
    return new Webinar(SCHEMA_VERSION, location, timezone, duration, capacity, sessions);
  }

  private Course course(Map<String, Object> input) {
    rejectUnknown(input, Set.of("schemaVersion", "dripDays", "modules"));
    int dripDays = integer(input, "dripDays", 0, 0, 3650);
    List<Map<String, Object>> rows = objectList(input.get("modules"), "modules", 50);
    List<CourseModule> modules = new ArrayList<>();
    Set<Long> ids = new HashSet<>();
    for (int index = 0; index < rows.size(); index++) {
      Map<String, Object> module = rows.get(index);
      rejectUnknown(module, Set.of("id", "title", "description", "position", "lessons"));
      Long id = optionalId(module, "id");
      uniqueId(ids, id, "course module");
      String title = optionalText(module, "title", "", 120);
      String moduleDescription = optionalText(module, "description", "", 2000);
      int position = integer(module, "position", index, 0, 100_000);
      List<Map<String, Object>> lessonRows = objectList(module.get("lessons"), "lessons", 200);
      List<CourseLesson> lessons = new ArrayList<>();
      Set<Long> lessonIds = new HashSet<>();
      for (int lessonIndex = 0; lessonIndex < lessonRows.size(); lessonIndex++) {
        Map<String, Object> lesson = lessonRows.get(lessonIndex);
        rejectUnknown(lesson, Set.of("id", "title", "videoUrl", "description", "content", "position"));
        Long lessonId = optionalId(lesson, "id");
        uniqueId(lessonIds, lessonId, "course lesson");
        String lessonTitle = optionalText(lesson, "title", "", 120);
        String video = optionalText(lesson, "videoUrl", "", 2048);
        if (!video.isBlank()) requireHttps(video, "course lesson videoUrl");
        String description = optionalText(lesson,
            lesson.containsKey("description") ? "description" : "content", "", 4000);
        int lessonPosition = integer(lesson, "position", lessonIndex, 0, 100_000);
        lessons.add(new CourseLesson(lessonId, lessonTitle, video, description, lessonPosition));
      }
      modules.add(new CourseModule(id, title, moduleDescription, position, List.copyOf(lessons)));
    }
    return new Course(SCHEMA_VERSION, dripDays, List.copyOf(modules));
  }

  private Membership membership(Map<String, Object> input) {
    rejectUnknown(input, Set.of("schemaVersion", "memberBenefits", "benefits", "welcomeMessage",
        "billingInterval", "plans"));
    List<String> benefits = benefits(input);
    String memberBenefits = optionalText(input, "memberBenefits", String.join("\n", benefits), 4000);
    String welcome = optionalText(input, "welcomeMessage", "", 2000);
    List<Map<String, Object>> rows = objectList(input.get("plans"), "plans", 20);
    List<PaymentPlan> plans = new ArrayList<>();
    Set<Long> ids = new HashSet<>();
    for (Map<String, Object> row : rows) {
      rejectUnknown(row, Set.of("id", "name", "amountSubunits", "interval", "intervalCount"));
      Long id = optionalId(row, "id");
      uniqueId(ids, id, "membership plan");
      String name = optionalText(row, "name", "", 80);
      int amount = integer(row, "amountSubunits", null, 0, 100_000_000);
      String interval = optionalText(row, "interval", "monthly", 20);
      if (!Set.of("daily", "weekly", "monthly", "annual").contains(interval))
        throw badRequest("membership interval must be daily, weekly, monthly, or annual");
      int count = integer(row, "intervalCount", 1, 1, 365);
      plans.add(new PaymentPlan(id, name, amount, interval, count));
    }
    return new Membership(SCHEMA_VERSION, benefits, memberBenefits, welcome, List.copyOf(plans));
  }

  private Fulfillment fulfillment(Map<String, Object> input) {
    rejectUnknown(input, Set.of("schemaVersion", "turnaroundDays", "deliveryFormat",
        "buyerInstructions", "checkoutFields"));
    int turnaround = integer(input, "turnaroundDays", 3, 1, 365);
    String format = optionalText(input, "deliveryFormat", "file-upload", 30);
    if (!Set.of("file-upload", "email", "call").contains(format))
      throw badRequest("unsupported fulfillment deliveryFormat");
    String instructions = optionalText(input, "buyerInstructions", "", 4000);
    List<Map<String, Object>> rows = objectList(input.get("checkoutFields"), "checkoutFields", 30);
    List<CheckoutField> fields = new ArrayList<>();
    Set<Long> ids = new HashSet<>();
    for (int index = 0; index < rows.size(); index++) {
      Map<String, Object> row = rows.get(index);
      rejectUnknown(row, Set.of("id", "label", "fieldType", "required", "position"));
      Long id = optionalId(row, "id");
      uniqueId(ids, id, "checkout field");
      String label = optionalText(row, "label", "", 120);
      String fieldType = optionalText(row, "fieldType", "text", 30);
      if (!Set.of("text", "textarea", "email", "phone", "number", "url").contains(fieldType))
        throw badRequest("unsupported checkout fieldType");
      boolean required = optionalBoolean(row, "required", false);
      int position = integer(row, "position", index, 0, 100_000);
      fields.add(new CheckoutField(id, label, fieldType, required, position));
    }
    return new Fulfillment(SCHEMA_VERSION, turnaround, format, instructions, List.copyOf(fields));
  }

  private Community community(Map<String, Object> input) {
    rejectUnknown(input, Set.of("schemaVersion", "platform", "accessUrl", "memberBenefits",
        "benefits", "welcomeMessage"));
    String platform = optionalText(input, "platform", "custom", 30);
    if (!Set.of("discord", "telegram", "whatsapp", "circle", "slack", "custom").contains(platform))
      throw badRequest("unsupported community platform");
    String accessUrl = optionalText(input, "accessUrl", "", 2048);
    if (!accessUrl.isBlank()) requireHttps(accessUrl, "accessUrl");
    List<String> benefits = benefits(input);
    String memberBenefits = optionalText(input, "memberBenefits", String.join("\n", benefits), 4000);
    String welcome = optionalText(input, "welcomeMessage", "", 2000);
    return new Community(SCHEMA_VERSION, platform, accessUrl, benefits, memberBenefits, welcome);
  }

  private List<Slot> slots(Object raw, int configuredDuration) {
    List<Map<String, Object>> rows = objectList(raw, "slots", 500);
    List<Slot> slots = new ArrayList<>();
    Set<Long> ids = new HashSet<>();
    for (Map<String, Object> row : rows) {
      rejectUnknown(row, Set.of("id", "startsAt", "endsAt"));
      Long id = optionalId(row, "id");
      uniqueId(ids, id, "meeting slot");
      Instant starts = instant(row.get("startsAt"), "meeting slot startsAt");
      Instant ends = instant(row.get("endsAt"), "meeting slot endsAt");
      long minutes = Duration.between(starts, ends).toMinutes();
      if (!ends.isAfter(starts) || minutes > 480)
        throw badRequest("meeting slot endsAt must be after startsAt and within 480 minutes");
      if (Math.abs(minutes - configuredDuration) > 1)
        throw badRequest("meeting slot length must match durationMinutes");
      slots.add(new Slot(id, starts, ends));
    }
    slots.sort(Comparator.comparing(Slot::startsAt));
    for (int i = 1; i < slots.size(); i++)
      if (slots.get(i).startsAt().isBefore(slots.get(i - 1).endsAt()))
        throw badRequest("meeting slots must not overlap");
    return List.copyOf(slots);
  }

  private List<WebinarSession> webinarSessions(Object raw, int defaultCapacity) {
    List<Map<String, Object>> rows = objectList(raw, "sessions", 100);
    List<WebinarSession> sessions = new ArrayList<>();
    Set<Long> ids = new HashSet<>();
    for (Map<String, Object> row : rows) {
      rejectUnknown(row, Set.of("id", "startsAt", "endsAt", "capacity", "joinUrl"));
      Long id = optionalId(row, "id");
      uniqueId(ids, id, "webinar session");
      Instant starts = instant(row.get("startsAt"), "webinar session startsAt");
      Instant ends = instant(row.get("endsAt"), "webinar session endsAt");
      if (!ends.isAfter(starts) || Duration.between(starts, ends).toMinutes() > 1440)
        throw badRequest("webinar session endsAt must be after startsAt and within 24 hours");
      int capacity = integer(row, "capacity", defaultCapacity, 1, 100_000);
      String joinUrl = optionalText(row, "joinUrl", "", 2048);
      if (!joinUrl.isBlank()) requireHttps(joinUrl, "joinUrl");
      sessions.add(new WebinarSession(id, starts, ends, capacity, joinUrl));
    }
    sessions.sort(Comparator.comparing(WebinarSession::startsAt));
    return List.copyOf(sessions);
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> objectList(Object raw, String label, int max) {
    if (raw == null) return List.of();
    if (!(raw instanceof List<?> list)) throw badRequest(label + " must be a list");
    if (list.size() > max) throw badRequest(label + " exceeds the supported item count");
    List<Map<String, Object>> result = new ArrayList<>();
    for (Object item : list) {
      if (!(item instanceof Map<?, ?> map)) throw badRequest(label + " items must be objects");
      for (Object key : map.keySet())
        if (!(key instanceof String)) throw badRequest(label + " keys must be strings");
      result.add((Map<String, Object>) map);
    }
    return result;
  }

  private static int schemaVersion(Map<String, Object> input) {
    int version = integer(input, "schemaVersion", SCHEMA_VERSION, 1, SCHEMA_VERSION);
    if (version != SCHEMA_VERSION) throw badRequest("unsupported product configuration schemaVersion");
    return version;
  }

  private static int integer(Map<String, Object> input, String key, Integer defaultValue,
      int min, int max) {
    Object raw = input.get(key);
    if (raw == null && defaultValue != null) return defaultValue;
    if (!(raw instanceof Number number)) throw badRequest(key + " must be a number");
    double value = number.doubleValue();
    if (!Double.isFinite(value) || value != Math.rint(value)) throw badRequest(key + " must be a whole number");
    long whole = number.longValue();
    if (whole < min || whole > max) throw badRequest(key + " is outside the supported range");
    return (int) whole;
  }

  private static Long optionalId(Map<String, Object> input, String key) {
    Object raw = input.get(key);
    if (raw == null) return null;
    if (!(raw instanceof Number number) || number.longValue() <= 0
        || number.doubleValue() != Math.rint(number.doubleValue()))
      throw badRequest(key + " must be a positive whole number");
    return number.longValue();
  }

  private static String requiredText(Map<String, Object> input, String key, int max) {
    String value = optionalText(input, key, "", max);
    if (value.isBlank()) throw badRequest(key + " is required");
    return value;
  }

  private static String optionalText(Map<String, Object> input, String key, String defaultValue,
      int max) {
    Object raw = input.get(key);
    if (raw == null) return defaultValue;
    if (!(raw instanceof String)) throw badRequest(key + " must be text");
    String value = ((String) raw).trim();
    if (value.length() > max) throw badRequest(key + " is too long");
    return value;
  }

  private static boolean optionalBoolean(Map<String, Object> input, String key, boolean defaultValue) {
    Object raw = input.get(key);
    if (raw == null) return defaultValue;
    if (!(raw instanceof Boolean value)) throw badRequest(key + " must be true or false");
    return value;
  }

  private static Instant instant(Object raw, String label) {
    if (!(raw instanceof String value) || value.isBlank()) throw badRequest(label + " is required");
    try { return Instant.parse(value.trim()); }
    catch (DateTimeException exception) { throw badRequest(label + " must be an ISO-8601 instant with timezone"); }
  }

  private static void requireHttps(String value, String label) {
    try {
      URI uri = URI.create(value);
      if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
          || uri.getUserInfo() != null) throw new IllegalArgumentException();
    } catch (IllegalArgumentException exception) {
      throw badRequest(label + " must be an absolute HTTPS URL");
    }
  }

  private static void rejectUnknown(Map<String, Object> input, Set<String> allowed) {
    for (String key : input.keySet())
      if (!allowed.contains(key)) throw badRequest("unsupported configuration field: " + key);
  }

  private static void uniqueId(Set<Long> ids, Long id, String label) {
    if (id != null && !ids.add(id)) throw badRequest(label + " id is duplicated");
  }

  private static List<String> benefits(Map<String, Object> input) {
    Object raw = input.get("benefits");
    if (raw == null) {
      String legacy = optionalText(input, "memberBenefits", "", 4000);
      if (legacy.isBlank()) return List.of();
      return legacy.lines().map(String::trim).filter(value -> !value.isBlank()).limit(50).toList();
    }
    if (!(raw instanceof List<?> list) || list.size() > 50)
      throw badRequest("benefits must be a list with at most 50 entries");
    List<String> result = new ArrayList<>();
    for (Object item : list) {
      if (!(item instanceof String value)) throw badRequest("benefits entries must be text");
      value = value.trim();
      if (value.length() > 240) throw badRequest("benefit is too long");
      if (!value.isBlank()) result.add(value);
    }
    return List.copyOf(result);
  }

  private Map<String, Object> slotMap(Slot slot) {
    Map<String, Object> value = new LinkedHashMap<>();
    if (slot.id() != null) value.put("id", slot.id());
    value.put("startsAt", slot.startsAt().toString());
    value.put("endsAt", slot.endsAt().toString());
    return value;
  }

  private Map<String, Object> sessionMap(WebinarSession session) {
    Map<String, Object> value = new LinkedHashMap<>();
    if (session.id() != null) value.put("id", session.id());
    value.put("startsAt", session.startsAt().toString());
    value.put("endsAt", session.endsAt().toString());
    value.put("capacity", session.capacity());
    value.put("joinUrl", session.joinUrl());
    return value;
  }

  private Map<String, Object> moduleMap(CourseModule module) {
    Map<String, Object> value = new LinkedHashMap<>();
    if (module.id() != null) value.put("id", module.id());
    value.put("title", module.title());
    value.put("description", module.description());
    value.put("position", module.position());
    value.put("lessons", module.lessons().stream().map(this::lessonMap).toList());
    return value;
  }

  private Map<String, Object> lessonMap(CourseLesson lesson) {
    Map<String, Object> value = new LinkedHashMap<>();
    if (lesson.id() != null) value.put("id", lesson.id());
    value.put("title", lesson.title());
    value.put("videoUrl", lesson.videoUrl());
    value.put("description", lesson.description());
    value.put("position", lesson.position());
    return value;
  }

  private Map<String, Object> planMap(PaymentPlan plan) {
    Map<String, Object> value = new LinkedHashMap<>();
    if (plan.id() != null) value.put("id", plan.id());
    value.put("name", plan.name());
    value.put("amountSubunits", plan.amountSubunits());
    value.put("interval", plan.interval());
    value.put("intervalCount", plan.intervalCount());
    return value;
  }

  private Map<String, Object> fieldMap(CheckoutField field) {
    Map<String, Object> value = new LinkedHashMap<>();
    if (field.id() != null) value.put("id", field.id());
    value.put("label", field.label());
    value.put("fieldType", field.fieldType());
    value.put("required", field.required());
    value.put("position", field.position());
    return value;
  }

  private static ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }
}
