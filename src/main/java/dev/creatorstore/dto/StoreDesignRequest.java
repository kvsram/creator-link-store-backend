package dev.creatorstore.dto;

public record StoreDesignRequest(
    String title,
    String tagline,
    String theme,
    String accentColor,
    String backgroundStyle,
    String buttonStyle,
    String fontStyle,
    Boolean showProducts,
    Boolean showLinks) {}
