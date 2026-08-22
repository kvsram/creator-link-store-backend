package dev.creatorstore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.creatorstore.dto.StoreDesignRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class StoreServiceTest {
  @Test
  void rejectsUnsupportedDesignValuesBeforeWritingToTheDatabase() {
    StoreService service = new StoreService(null, null);
    StoreDesignRequest request = new StoreDesignRequest("My Store", "A useful tagline",
        "javascript", "#6842d7", "soft-gradient", "rounded", "modern", true, true);

    assertThatThrownBy(() -> service.updateDesign(1, request))
        .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
          assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
          assertThat(exception.getReason()).isEqualTo("unsupported storefront design value");
        });
  }
}
