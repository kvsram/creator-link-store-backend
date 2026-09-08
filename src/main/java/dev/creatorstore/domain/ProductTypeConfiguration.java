package dev.creatorstore.domain;

import java.time.Instant;
import java.util.List;

/** Validated, canonical type-specific product configuration. */
public sealed interface ProductTypeConfiguration permits
    ProductTypeConfiguration.Delivery,
    ProductTypeConfiguration.Meeting,
    ProductTypeConfiguration.Webinar,
    ProductTypeConfiguration.Course,
    ProductTypeConfiguration.Membership,
    ProductTypeConfiguration.Fulfillment,
    ProductTypeConfiguration.Community {

  int schemaVersion();

  record Delivery(int schemaVersion, String deliveryMode, String redirectUrl, boolean collectName,
                  boolean collectEmail, boolean collectPhone,
                  String consentText) implements ProductTypeConfiguration {}

  record Meeting(int schemaVersion, String location, String locationDetails, String timezone,
                 int durationMinutes, int maxAttendees, int minNoticeHours, int bufferMinutes,
                 List<Slot> slots) implements ProductTypeConfiguration {}

  record Slot(Long id, Instant startsAt, Instant endsAt) {}

  record Webinar(int schemaVersion, String location, String timezone, int durationMinutes,
                 int capacity, List<WebinarSession> sessions) implements ProductTypeConfiguration {}

  record WebinarSession(Long id, Instant startsAt, Instant endsAt, int capacity,
                        String joinUrl) {}

  record Course(int schemaVersion, int dripDays,
                List<CourseModule> modules) implements ProductTypeConfiguration {}

  record CourseModule(Long id, String title, String description, int position,
                      List<CourseLesson> lessons) {}

  record CourseLesson(Long id, String title, String videoUrl, String description, int position) {}

  record Membership(int schemaVersion, List<String> benefits, String memberBenefits,
                    String welcomeMessage,
                    List<PaymentPlan> plans) implements ProductTypeConfiguration {}

  record PaymentPlan(Long id, String name, int amountSubunits, String interval,
                     int intervalCount) {}

  record Fulfillment(int schemaVersion, int turnaroundDays, String deliveryFormat,
                     String buyerInstructions,
                     List<CheckoutField> checkoutFields) implements ProductTypeConfiguration {}

  record CheckoutField(Long id, String label, String fieldType, boolean required, int position) {}

  record Community(int schemaVersion, String platform, String accessUrl, List<String> benefits,
                   String memberBenefits, String welcomeMessage) implements ProductTypeConfiguration {}
}
