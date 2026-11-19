package com.dailybrief.delivery;

import com.dailybrief.common.MailGateway;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

@Component
public class ResendMailGateway implements MailGateway {
    private final String key, from;
    private final ObjectMapper json;
    private final HttpClient client =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public ResendMailGateway(
            @Value("${RESEND_API_KEY:}") String key,
            @Value("${RESEND_FROM:}") String from,
            ObjectMapper json) {
        this.key = key;
        this.from = from;
        this.json = json;
    }

    @Override
    public String sender() {
        return from;
    }

    @Override
    public Duration idempotencyWindow() {
        return Duration.ofHours(24);
    }

    @Override
    public Submission submit(Message message) {
        String sender = message.sender() == null ? from : message.sender();
        if (key.isBlank() || sender.isBlank())
            return new Submission(Status.PERMANENT_REJECTION, null);
        try {
            UUID.fromString(message.id());
            var payload =
                    Map.of(
                            "from",
                            sender,
                            "to",
                            List.of(message.recipient()),
                            "subject",
                            message.subject(),
                            "text",
                            message.text(),
                            "html",
                            message.html(),
                            "tags",
                            List.of(Map.of("name", "delivery_id", "value", message.id())));
            var request =
                    HttpRequest.newBuilder(URI.create("https://api.resend.com/emails"))
                            .timeout(Duration.ofSeconds(20))
                            .header("Authorization", "Bearer " + key)
                            .header("Content-Type", "application/json")
                            .header("Idempotency-Key", message.id())
                            .POST(
                                    HttpRequest.BodyPublishers.ofString(
                                            json.writeValueAsString(payload)))
                            .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                String id = json.readTree(response.body()).path("id").asText(null);
                return id == null
                        ? new Submission(Status.UNKNOWN, null)
                        : new Submission(Status.ACCEPTED, id);
            }
            if (response.statusCode() == 429)
                return new Submission(Status.RETRYABLE_REJECTION, null);
            if (response.statusCode() >= 400
                    && response.statusCode() < 500
                    && response.statusCode() != 409)
                return new Submission(Status.PERMANENT_REJECTION, null);
            return new Submission(Status.UNKNOWN, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Submission(Status.UNKNOWN, null);
        } catch (Exception e) {
            return new Submission(Status.UNKNOWN, null);
        }
    }
}
