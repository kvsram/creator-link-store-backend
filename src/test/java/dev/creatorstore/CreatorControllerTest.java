package dev.creatorstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.creatorstore.controller.HealthController;
import dev.creatorstore.dto.RegisterRequest;
import dev.creatorstore.identity.ClientContext;
import dev.creatorstore.identity.SessionService;
import dev.creatorstore.repository.CreatorRepository;
import dev.creatorstore.service.AuthenticationService;
import dev.creatorstore.service.IdentityAuthenticationService;
import java.time.OffsetDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class CreatorControllerTest {
  @Test
  void healthIsAvailableWithoutDatabaseAccess() {
    HealthController controller = new HealthController();
    assertThat(controller.health()).containsEntry("status", "ok");
  }

  @Test
  void registrationRejectsUnsafeInputBeforeDatabaseAccess() {
    IdentityAuthenticationService service = new IdentityAuthenticationService(
        null, null, null, null, null, null, null, null, null, null);
    assertThatThrownBy(() -> service.register(
        new RegisterRequest("x", "Name", "bad-email", null, "short"),
        ClientContext.unknown(), false))
        .isInstanceOfSatisfying(ResponseStatusException.class,
            exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
  }

  @Test
  void registrationRejectsReservedRouteHandleBeforeDatabaseAccess() {
    IdentityAuthenticationService service = new IdentityAuthenticationService(
        null, null, null, null, null, null, null, null, null, null);

    assertThatThrownBy(() -> service.register(
        new RegisterRequest("SignUp", "Creator Name", "creator@example.com", null,
            "safe-password-long"), ClientContext.unknown(), false))
        .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
          assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
          assertThat(exception.getReason()).contains("reserved");
        });
  }

  @Test
  void uniquenessTreatsReservedRouteAsUnavailableWithoutHandleLookup() {
    CreatorRepository creators = mock(CreatorRepository.class);
    AuthenticationService service = new AuthenticationService(creators, null);

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
    AuthenticationService service = new AuthenticationService(creators, null);

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
    IdentityAuthenticationService identity = mock(IdentityAuthenticationService.class);
    SessionService.IssuedSession issued = new SessionService.IssuedSession(
        "browser-token", OffsetDateTime.parse("2030-01-01T00:00:00Z"));
    RegisterRequest request = new RegisterRequest(" Creator_Name ", " Creator Name ",
        " Creator@Example.com ", " ", "safe-password-long");
    Map<String, Object> expected = Map.of("id", 42L, "handle", "creator_name",
        "displayName", "Creator Name", "plan", "free", "authenticated", true,
        "onboarding_next", "/dashboard/");
    when(identity.register(eq(request), any(ClientContext.class), eq(false)))
        .thenReturn(new IdentityAuthenticationService.Registration(expected, issued));
    AuthenticationService service = new AuthenticationService(creators, identity);

    AuthenticationService.Registration registration = service.register(request);
    Map<String, Object> result = registration.account();

    assertThat(result).containsEntry("id", 42L)
        .containsEntry("handle", "creator_name")
        .containsEntry("displayName", "Creator Name")
        .containsEntry("plan", "free")
        .containsEntry("authenticated", true)
        .containsEntry("onboarding_next", "/dashboard/");
    assertThat(registration.session()).isEqualTo(issued);
    verify(identity).register(eq(request), any(ClientContext.class), eq(false));
  }
}
