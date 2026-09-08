package dev.creatorstore.dto;

import java.util.Map;

/**
 * Creator-side aggregate product request.
 *
 * <p>The creator id in older clients is deliberately ignored. Ownership always comes from the
 * authenticated session. The presentation and configuration fields are optional for wire
 * compatibility with the original two-request editor, while new clients should send them so the
 * product and its type-specific configuration are committed in one transaction.</p>
 */
public record ProductRequest(
    long creatorId,
    String type,
    String title,
    String description,
    int priceSubunits,
    String status,
    int position,
    String subtitle,
    String callToAction,
    String thumbnailStyle,
    Map<String, Object> configuration) {

  public ProductRequest(long creatorId, String type, String title, String description,
      int priceSubunits, String status, int position) {
    this(creatorId, type, title, description, priceSubunits, status, position,
        null, null, null, null);
  }
}
