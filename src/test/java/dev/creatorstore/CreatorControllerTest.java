package dev.creatorstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.creatorstore.controller.HealthController;
import dev.creatorstore.dto.RegisterRequest;
import dev.creatorstore.identity.SessionService;
import dev.creatorstore.repository.CreatorRepository;
import dev.creatorstore.repository.FeatureRepository;
import dev.creatorstore.repository.StoreRepository;
import dev.creatorstore.service.AuthenticationService;
import java.time.OffsetDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

class CreatorControllerTest {
  @Test
  void healthIsAvailableWithoutDatabaseAccess() {
    HealthController controller = new HealthController();
    assertThat(controller.health()).containsEntry("status", "ok");
  }

  @Test
  void registrationRejectsUnsafeInputBeforeDatabaseAccess() {
    AuthenticationService service = new AuthenticationService(null, null, null, null, null);
    assertThatThrownBy(() -> service.register(
        new RegisterRequest("x", "Name", "bad-email", null, "short")))
        .isInstanceOfSatisfying(ResponseStatusException.class,
            exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
  }

  @Test
  void registrationRejectsReservedRouteHandleBeforeDatabaseAccess() {
    AuthenticationService service = new AuthenticationService(null, null, null, null, null);

    assertThatThrownBy(() -> service.register(
        new RegisterRequest("SignUp", "Creator Name", "creator@example.com", null,
            "safe-password")))
        .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
          assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
          assertThat(exception.getReason()).contains("reserved");
        });
  }

  @Test
  void uniquenessTreatsReservedRouteAsUnavailableWithoutHandleLookup() {
    CreatorRepository creators = mock(CreatorRepository.class);
    AuthenticationService service = new AuthenticationService(creators, null, null, null, null);

    Map<String, Object> result = service.uniqueness(
        Map.of("username", " Dashboard ", "email", " Creator@Example.com "));

    assertThat(result).containsEntry("handle_taken", true)
        .containsEntry("username_taken", true)
        .containsEntry("available", false);
    assertThat(result).doesNotContainKey("email_taken");
    verify(creators, never()).handleExists(anyString());
  }

  @Test
  void uniquenessIgnoresEmailExistenceAndAcceptsHandleField() {
    CreatorRepository creators = mock(CreatorRepository.class);
    when(creators.handleExists("fresh_handle")).thenReturn(false);
    AuthenticationService service = new AuthenticationService(creators, null, null, null, null);

    Map<String, Object> result = service.uniqueness(
        Map.of("handle", " Fresh_Handle ", "email", "known@example.com"));

    assertThat(result).containsEntry("handle_taken", false)
        .containsEntry("username_taken", false)
        .containsEntry("available", true)
        .doesNotContainKey("email_taken");
    verify(creators).handleExists("fresh_handle");
    verify(creators, never()).emailExists(anyString());
  }

  @Test
  void registrationNormalizesPersonalDetailsAndCreatesFreeStore() {
    CreatorRepository creators = mock(CreatorRepository.class);
    StoreRepository stores = mock(StoreRepository.class);
    FeatureRepository features = mock(FeatureRepository.class);
    BCryptPasswordEncoder encoder = mock(BCryptPasswordEncoder.class);
    SessionService sessions = mock(SessionService.class);
    when(encoder.encode("safe-password")).thenReturn("bcrypt-hash");
    when(creators.create("creator_name", "Creator Name", "creator@example.com", null,
        "bcrypt-hash")).thenReturn(42L);
    SessionService.IssuedSession issued = new SessionService.IssuedSession(
        "browser-token", OffsetDateTime.parse("2030-01-01T00:00:00Z"));
    when(sessions.issue(42L)).thenReturn(issued);
    AuthenticationService service = new AuthenticationService(
        creators, stores, features, encoder, sessions);

    AuthenticationService.Registration registration = service.register(new RegisterRequest(
        " Creator_Name ", " Creator Name ", " Creator@Example.com ", " ", "safe-password"));
    Map<String, Object> result = registration.account();

    assertThat(result).containsEntry("id", 42L)
        .containsEntry("handle", "creator_name")
        .containsEntry("displayName", "Creator Name")
        .containsEntry("plan", "free")
        .containsEntry("authenticated", true)
        .containsEntry("onboarding_next", "/dashboard/");
    assertThat(registration.session()).isEqualTo(issued);
    verify(creators).create("creator_name", "Creator Name", "creator@example.com", null,
        "bcrypt-hash");
    verify(stores).createDefault(42L, "Creator Name's Store");
    verify(features).createNotificationDefaults(42L);
    verify(sessions).issue(42L);
  }
}
