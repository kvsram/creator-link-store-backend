package com.creatorstore.security;

/**
 * Stored as the enum name in admin_users.role. Becomes the JWT "admin_role" claim and the
 * Spring authority ROLE_<name>, so endpoints can require e.g. {@code @PreAuthorize("hasRole('FINANCE')")}.
 */
public enum AdminRole {
    SUPER_ADMIN,   // everything
    SUPPORT,       // creators, tickets, read-only money
    FINANCE        // payouts, refunds, disputes
}
