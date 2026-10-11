/**
 * PAYMENT MODULE (not yet implemented).
 *
 * Owns tables: payments, refunds, disputes, payout_batches, creator_payouts, creator_subscriptions.
 * Responsibilities: talk to the payment provider (Stripe / Razorpay) through one interface,
 * verify webhook signatures, record provider ids, compute platform fee, run payout batches,
 * and bill creators their monthly plan. Money is always BIGINT minor units + ISO currency.
 * Card data never touches this system; only provider ids are stored.
 * Depends on: order, creator.
 * Migrations: V9__payments.sql, V10__payouts.sql, V11__creator_subscriptions.sql
 */
package com.creatorstore.payment;
