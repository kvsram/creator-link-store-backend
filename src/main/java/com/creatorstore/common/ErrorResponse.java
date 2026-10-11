package com.creatorstore.common;

/** The JSON body of every error response: {"code": "USERNAME_TAKEN", "message": "..."} */
public record ErrorResponse(String code, String message) {}
