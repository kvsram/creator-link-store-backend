/**
 * PRODUCT MODULE (not yet implemented).
 *
 * Owns tables: products, prices.
 * Responsibilities: a creator creates/edits/hides products (digital file, course, physical, booking),
 * each with one or more prices in minor units (BIGINT paise/cents, never floating point).
 * Public read access is through the storefront module, never directly.
 *
 * Planned layout (same as every module):
 *   Product.java, Price.java            - JPA entities
 *   ProductRepository.java              - used only by ProductService
 *   ProductService.java                 - the API other modules call
 *   ProductController.java              - /api/products/** (ROLE_CREATOR, owner-checked)
 *   ProductDtos.java                    - request/response records
 * Migration: V2__products.sql (see docs/planned-schema.sql)
 */
package com.creatorstore.product;
