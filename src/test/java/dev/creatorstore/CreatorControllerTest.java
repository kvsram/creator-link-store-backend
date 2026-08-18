package dev.creatorstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.creatorstore.controller.HealthController;
import dev.creatorstore.dto.RegisterRequest;
import dev.creatorstore.service.AuthenticationService;
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
    AuthenticationService service = new AuthenticationService(null, null, null, null);
    assertThatThrownBy(() -> service.register(
        new RegisterRequest("x", "Name", "bad-email", null, "short")))
        .isInstanceOfSatisfying(ResponseStatusException.class,
            exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
  }
}
