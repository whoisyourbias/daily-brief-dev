package com.dailybrief.common;

import java.time.Duration;

public interface MailGateway {

    Submission submit(Message message);

    Duration idempotencyWindow();

    default String sender() {
        return "";
    }

    record Message(
            String id, String recipient, String subject, String text, String html, String sender) {
        public Message(String id, String recipient, String subject, String text, String html) {
            this(id, recipient, subject, text, html, null);
        }
    }

    record Submission(Status status, String providerId) {}

    enum Status {
        ACCEPTED,
        RETRYABLE_REJECTION,
        PERMANENT_REJECTION,
        UNKNOWN
    }
}
