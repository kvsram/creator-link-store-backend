/**
 * ORDER MODULE (not yet implemented).
 *
 * Owns tables: orders, fulfillments.
 * Responsibilities: an order is the customer's intent to buy at a given price (snapshot of amount and currency);
 * status machine PENDING -> PAID -> (REFUNDED | PARTIALLY_REFUNDED | DISPUTED) or PENDING -> FAILED.
 * Fulfillment delivers the product after payment (signed expiring file URL, booking confirmation, access grant).
 * Depends on: product, customer, payment (events), booking.
 * Migration: V8__orders.sql
 */
package com.creatorstore.order;
