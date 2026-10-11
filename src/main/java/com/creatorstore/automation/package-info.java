/**
 * AUTOMATION MODULE (not yet implemented).
 *
 * Owns tables: social_accounts, automation_rules, dm_logs, tracking_links.
 * Responsibilities:
 *   - connect an Instagram Professional account (Meta OAuth; access token stored encrypted);
 *   - keyword rules ("comment SKIN on post X -> send link to product Y");
 *   - receive Meta webhooks (comments / DMs), match rules, queue an auto-DM;
 *   - send DMs from a queue with retries and Meta rate limits (24-hour window, per-hour caps);
 *   - mint a tracking link per DM so the storefront can show a personalised page and attribute the sale.
 * Depends on: creator, product, customer, notification.
 * Migrations: V5__social_accounts.sql, V6__automation.sql
 */
package com.creatorstore.automation;
