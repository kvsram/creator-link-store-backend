package com.creatorstore.auth;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Device details stored with a session so a creator can later see "logged in from Chrome on 10 Oct".
 *
 * Behind a reverse proxy, set {@code server.forward-headers-strategy=native} (or framework) so
 * {@code getRemoteAddr()} returns the real client IP instead of the proxy's.
 */
public record ClientInfo(String userAgent, String ipAddress) {

    public static ClientInfo from(HttpServletRequest request) {
        String ua = request.getHeader("User-Agent");
        if (ua != null && ua.length() > 255) {
            ua = ua.substring(0, 255);
        }
        String ip = request.getRemoteAddr();
        if (ip != null && ip.length() > 45) {
            ip = ip.substring(0, 45);
        }
        return new ClientInfo(ua, ip);
    }
}
