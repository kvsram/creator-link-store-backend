/**
 * SUPPORT MODULE (not yet implemented).
 *
 * Owns tables: support_tickets, support_messages, system_errors, audit_logs.
 * Responsibilities: tickets from creators and customers, internal admin notes, the error list the admin
 * dashboard shows, and the append-only audit log that every admin action writes to in the same transaction.
 * Depends on: admin, creator, customer, order.
 * Migration: V12__support.sql
 */
package com.creatorstore.support;
