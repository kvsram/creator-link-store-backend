/**
 * LOCAL DEVELOPMENT ONLY.
 *
 * Everything in this package is active only under the {@code local} Spring profile
 * (docker-compose sets SPRING_PROFILES_ACTIVE=local). Nothing here runs in the cloud, and nothing in
 * the business modules may depend on this package.
 *
 *   DevSeed.java   - creates a demo creator through the real CreatorService so a fresh
 *                    `docker compose up` has an account to log in with
 */
package com.creatorstore.local;
