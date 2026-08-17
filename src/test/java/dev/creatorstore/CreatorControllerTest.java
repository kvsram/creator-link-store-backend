package dev.creatorstore;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

class CreatorControllerTest {
  @Test
  void healthIsAvailableWithoutDatabaseAccess() {
    CreatorController controller = new CreatorController(null);
    assertThat(controller.health()).containsEntry("status", "ok");
  }

  @Test
  void registrationRejectsUnsafeInputBeforeDatabaseAccess() {
    CreatorController controller = new CreatorController((JdbcTemplate) null);
    var response = controller.register(new CreatorController.Register("x", "Name", "bad-email", "short"));
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }
}
