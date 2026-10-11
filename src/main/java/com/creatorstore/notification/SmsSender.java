package com.creatorstore.notification;

/**
 * Sends one SMS. Exactly one implementation is active, chosen by {@code app.sms.provider}.
 *
 * To add a real provider (Twilio, MSG91, ...): implement this interface, annotate the class with
 * {@code @Component} and {@code @ConditionalOnProperty(name = "app.sms.provider", havingValue = "twilio")},
 * and set SMS_PROVIDER=twilio. Nothing else in the codebase changes.
 */
public interface SmsSender {

    void send(String toPhoneE164, String message);
}
