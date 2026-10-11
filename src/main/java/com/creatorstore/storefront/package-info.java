/**
 * STOREFRONT MODULE (not yet implemented).
 *
 * Owns table: store_pages.
 * Responsibilities: the PUBLIC page at {baseUrl}/{username}: theme, published flag, the list of visible products.
 * This is the only module with unauthenticated read endpoints (/api/public/stores/{slug}).
 * The page is served only when the creator is ACTIVE and the page is published, otherwise 404.
 * Depends on: creator, product.
 * Migration: V4__store_pages.sql
 */
package com.creatorstore.storefront;
