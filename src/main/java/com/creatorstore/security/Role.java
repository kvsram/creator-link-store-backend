package com.creatorstore.security;

/** The two kinds of principal in the system. Stored in the JWT "role" claim. */
public enum Role {
    CREATOR,
    ADMIN
}
