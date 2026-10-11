/**
 * BOOKING MODULE (not yet implemented).
 *
 * Owns tables: booking_sessions, booking_slots.
 * Responsibilities: a product of type BOOKING has a duration, the creator's IANA timezone and capacity;
 * slots are concrete start/end instants; booked_count never exceeds capacity (enforced with a row lock on the slot).
 * Depends on: product (the parent product), order (a paid order reserves a slot).
 * Migration: V3__bookings.sql
 */
package com.creatorstore.booking;
