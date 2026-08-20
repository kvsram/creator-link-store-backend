package dev.creatorstore.service;

import dev.creatorstore.integration.InstagramClient;
import dev.creatorstore.repository.InstagramAutomationRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class InstagramDmWorker {
  private final InstagramJobService jobs;
  private final InstagramAutomationRepository repository;
  private final InstagramClient instagram;
  private final String mode;
  private final String appSecret;
  private final boolean enabled;

  public InstagramDmWorker(InstagramJobService jobs, InstagramAutomationRepository repository,
      InstagramClient instagram, @Value("${instagram.mode:disabled}") String mode,
      @Value("${instagram.app-secret:}") String appSecret,
      @Value("${instagram.autodm-worker-enabled:true}") boolean enabled) {
    this.jobs = jobs;
    this.repository = repository;
    this.instagram = instagram;
    this.mode = mode;
    this.appSecret = appSecret;
    this.enabled = enabled;
  }

  @Scheduled(fixedDelayString = "${instagram.autodm-poll-ms:1000}")
  public void drain() {
    if (!enabled || !Set.of("test", "live").contains(mode) || !instagram.configured(appSecret)) return;
    for (int count = 0; count < 20; count++) {
      Map<String, Object> job = jobs.claimOne();
      if (job.isEmpty()) return;
      process(job);
    }
  }

  private void process(Map<String, Object> job) {
    long jobId = number(job.get("id"));
    long automationId = number(job.get("automation_id"));
    int priorAttempts = ((Number) job.get("attempt_count")).intValue();
    Instant occurredAt = instant(job.get("occurred_at"));
    if (occurredAt.plus(7, ChronoUnit.DAYS).isBefore(Instant.now())) {
      repository.dead(jobId, "Meta private-reply seven-day window expired");
      return;
    }
    try {
      Map<String, Object> result = instagram.sendPrivateReply(
          String.valueOf(job.get("comment_id")), String.valueOf(job.get("message")));
      repository.sent(jobId, automationId, String.valueOf(result.get("message_id")));
    } catch (InstagramClient.ProviderRejectedException rejected) {
      if (!rejected.retryable() || priorAttempts >= 7) {
        repository.dead(jobId, rejected.getMessage());
      } else {
        repository.retry(jobId, backoff(priorAttempts), rejected.getMessage());
      }
    } catch (Exception failure) {
      if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
      if (priorAttempts >= 7) repository.dead(jobId, failure.getClass().getSimpleName());
      else repository.retry(jobId, backoff(priorAttempts), failure.getClass().getSimpleName());
    }
  }

  private static int backoff(int priorAttempts) {
    return Math.min(900, 5 * (1 << Math.min(priorAttempts, 7)));
  }

  private static long number(Object value) {
    return ((Number) value).longValue();
  }

  private static Instant instant(Object value) {
    if (value instanceof Instant instant) return instant;
    if (value instanceof OffsetDateTime dateTime) return dateTime.toInstant();
    if (value instanceof Timestamp timestamp) return timestamp.toInstant();
    return Instant.parse(String.valueOf(value));
  }
}
