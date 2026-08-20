package dev.creatorstore.service;

import dev.creatorstore.repository.InstagramAutomationRepository;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InstagramJobService {
  private final InstagramAutomationRepository repository;

  public InstagramJobService(InstagramAutomationRepository repository) {
    this.repository = repository;
  }

  @Transactional
  public Map<String, Object> claimOne() {
    List<Map<String, Object>> jobs = repository.claimOne();
    return jobs.isEmpty() ? Map.of() : jobs.get(0);
  }
}
