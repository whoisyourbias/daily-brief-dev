package com.dailybrief.operations;

import com.dailybrief.delivery.DeliveryService;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@ConditionalOnProperty(name = "dailybrief.reconcile.id")
@ConditionalOnNotWebApplication
final class SendReconciliation implements ApplicationRunner {

    private final Environment environment;
    private final DeliveryService delivery;

    SendReconciliation(Environment environment, DeliveryService delivery) {
        this.environment = environment;
        this.delivery = delivery;
    }

    @Override
    public void run(ApplicationArguments args) {
        UUID id = UUID.fromString(environment.getRequiredProperty("dailybrief.reconcile.id"));
        String outcome = environment.getRequiredProperty("dailybrief.reconcile.outcome");
        if (!"accepted".equals(outcome) && !"unsent".equals(outcome)) {
            throw new IllegalArgumentException(
                    "Reconciliation outcome must be accepted or unsent.");
        }
        boolean accepted = "accepted".equals(outcome);
        String providerId = environment.getProperty("dailybrief.reconcile.provider-id");
        if (accepted && (providerId == null || providerId.isBlank())) {
            throw new IllegalArgumentException(
                    "Provider message ID is required for accepted reconciliation.");
        }
        if (!delivery.resolveFromProviderEvidence(id, accepted, providerId)) {
            throw new IllegalStateException(
                    "The delivery could not be reconciled in its current state.");
        }
    }
}
