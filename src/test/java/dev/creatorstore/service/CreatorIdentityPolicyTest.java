package dev.creatorstore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class CreatorIdentityPolicyTest {

  @Test
  void createsReadableSafeHandleBasesWithoutUsingPrivateEmailData() {
    assertThat(CreatorIdentityPolicy.handleBase("K.V. Sriram 89-8"))
        .isEqualTo("k_v_sriram_89_8");
    assertThat(CreatorIdentityPolicy.handleBase("José Creates"))
        .isEqualTo("jose_creates");
    assertThat(CreatorIdentityPolicy.handleBase("సృష్టికర్త 🎨"))
        .isEqualTo("creator");
    assertThat(CreatorIdentityPolicy.handleBase("A"))
        .isEqualTo("creator");
    assertThat(CreatorIdentityPolicy.handleBase("A very long creator name ".repeat(4)))
        .matches("[a-z0-9_]{3,40}")
        .hasSize(40);
  }

  @Test
  void addsBoundedRandomSuffixesForReservedNamesAndCollisions() {
    assertThat(CreatorIdentityPolicy.suffixedHandle(
        "A very long creator name that must be shortened", "01234567-89ab-cdef"))
        .isEqualTo("a_very_long_creator_name_th_0123456789ab")
        .matches("[a-z0-9_]{3,40}")
        .hasSize(40);
  }

  @Test
  void passwordPolicyUsesUnicodeCodePointsAndTwelveCharacterMinimum() {
    IdentityAuthenticationService.validatePassword("a".repeat(12));
    IdentityAuthenticationService.validatePassword("😀".repeat(12));
    IdentityAuthenticationService.validatePassword("a".repeat(128));

    assertThatThrownBy(() -> IdentityAuthenticationService.validatePassword("a".repeat(11)))
        .isInstanceOfSatisfying(ResponseStatusException.class,
            error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    assertThatThrownBy(() -> IdentityAuthenticationService.validatePassword("😀".repeat(11)))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> IdentityAuthenticationService.validatePassword("a".repeat(129)))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> IdentityAuthenticationService.validatePassword(" ".repeat(12)))
        .isInstanceOf(ResponseStatusException.class);
  }
}
