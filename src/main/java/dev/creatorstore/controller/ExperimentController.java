package dev.creatorstore.controller;

import dev.creatorstore.service.UserExperienceService;
import java.util.Map;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ExperimentController {
  private final UserExperienceService experience;

  public ExperimentController(UserExperienceService experience) {
    this.experience = experience;
  }

  @PutMapping("/api/v1/experiments/variant-assignment")
  public Map<String, Object> saveVariant(@RequestBody Map<String, Object> body) {
    return experience.saveVariant(body);
  }
}
