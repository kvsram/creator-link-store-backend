package dev.creatorstore.dto;

import java.util.Map;

public record ProductConfigurationRequest(
    String subtitle,
    String callToAction,
    String thumbnailStyle,
    Map<String, Object> configuration) {}
