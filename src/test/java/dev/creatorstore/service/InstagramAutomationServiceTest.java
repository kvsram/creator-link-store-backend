package dev.creatorstore.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class InstagramAutomationServiceTest {
  @Test
  void anyMatchesCaseInsensitiveSubstring() {
    assertTrue(InstagramAutomationService.matches("Please send GUIDE", "guide\nprice", "any"));
  }

  @Test
  void allRequiresEveryKeyword() {
    assertTrue(InstagramAutomationService.matches("guide price please", "guide\nprice", "all"));
    assertFalse(InstagramAutomationService.matches("guide please", "guide\nprice", "all"));
  }

  @Test
  void exactDoesNotMatchLargerComment() {
    assertTrue(InstagramAutomationService.matches("guide", "guide\nprice", "exact"));
    assertFalse(InstagramAutomationService.matches("send guide", "guide\nprice", "exact"));
  }

  @Test
  void emptyKeywordsNeverMatch() {
    assertFalse(InstagramAutomationService.matches("anything", "", "any"));
  }
}
