package com.dailybrief.delivery;

import static org.junit.jupiter.api.Assertions.*;

import com.dailybrief.common.MailGateway;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

class DeliveryServicePostgresTest {
    static PostgreSQLContainer<?> container;
    static String url, user, password;
    JdbcTemplate db, admin;
    String schema;
    MutableClock clock;
    FakeGateway gateway;
    DeliveryService service;
    static final Instant CUT = Instant.parse("2026-11-19T00:00:00Z");

    @BeforeAll
    static void database() {
        url = System.getenv("DAILYBRIEF_TEST_DB_URL");
        user = System.getenv().getOrDefault("DAILYBRIEF_TEST_DB_USER", "postgres");
        password = System.getenv().getOrDefault("DAILYBRIEF_TEST_DB_PASSWORD", "postgres");
        if (url == null || url.isBlank()) {
            try {
                container = new PostgreSQLContainer<>("postgres:16-alpine");
                container.start();
                url = container.getJdbcUrl();
                user = container.getUsername();
                password = container.getPassword();
            } catch (Throwable unavailable) {
                Assumptions.assumeTrue(
                        false,
                        "PostgreSQL requires Docker or DAILYBRIEF_TEST_DB_URL: "
                                + unavailable.getMessage());
            }
        }
    }

    @AfterAll
    static void stop() {
        if (container != null) container.stop();
    }

    @BeforeEach
    void setup() {
        schema = "delivery_test_" + UUID.randomUUID().toString().replace("-", "");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        admin.execute("CREATE SCHEMA " + schema);
        String scoped = url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        var ds = new DriverManagerDataSource(scoped, user, password);
        Flyway.configure().dataSource(ds).schemas(schema).load().migrate();
        db = new JdbcTemplate(ds);
        clock = new MutableClock(CUT);
        gateway = new FakeGateway();
        service = new DeliveryService(db, new DataSourceTransactionManager(ds), gateway, clock);
    }

    @AfterEach
    void cleanup() {
        if (admin != null && schema != null) admin.execute("DROP SCHEMA " + schema + " CASCADE");
    }

    long subscriber(String email, String project) {
        long id =
                db.queryForObject(
                        "INSERT INTO brief_subscriber(email,status,technology_enabled) VALUES"
                            + " (?,'ACTIVE',true) RETURNING id",
                        Long.class,
                        email);
        db.update(
                "INSERT INTO brief_project_selection VALUES (?,?,?)",
                id,
                project,
                Timestamp.from(CUT.minusSeconds(1000)));
        return id;
    }

    void release(long id, String project) {
        db.update(
                "INSERT INTO brief_release(id,project,version,url,published_at) VALUES (?,?,?,"
                    + " ?,?)",
                id,
                project,
                "v1",
                "https://example.com/" + id,
                Timestamp.from(CUT.minusSeconds(10)));
    }

    @Test
    void staleWorkerCannotOverwriteAcceptedTakeoverOrReleaseAssignment() throws Exception {
        subscriber("a@example.test", "REACT");
        release(1, "REACT");
        gateway.blockFirst = true;
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> first = executor.submit(() -> service.prepareAndSend(CUT));
            assertTrue(gateway.entered.await(10, TimeUnit.SECONDS));
            UUID id = db.queryForObject("SELECT id FROM brief_delivery", UUID.class);
            assertFalse(service.resolveFromProviderEvidence(id, false, null));
            clock.now.set(CUT.plusSeconds(121));
            service.retry();
            gateway.release.countDown();
            first.get(10, TimeUnit.SECONDS);
            assertEquals(
                    "SENT", db.queryForObject("SELECT state FROM brief_delivery", String.class));
            assertEquals(
                    1,
                    db.queryForObject(
                            "SELECT count(*) FROM brief_delivery_release", Integer.class));
            assertEquals(2, gateway.messages.size());
            assertEquals(gateway.messages.get(0), gateway.messages.get(1));
        } finally {
            gateway.release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void definiteRejectionReleasesAssignmentsAndSelectionsAreIsolated() {
        subscriber("a@example.test", "REACT");
        subscriber("b@example.test", "SPRING_BOOT");
        release(1, "REACT");
        release(2, "SPRING_BOOT");
        gateway.rejectFirst = true;
        service.prepareAndSend(CUT);
        assertEquals(2, gateway.messages.size());
        for (var m : gateway.messages) {
            if (m.recipient().equals("a@example.test")) {
                assertTrue(m.text().contains("React"));
                assertFalse(m.text().contains("Spring Boot"));
            } else {
                assertTrue(m.text().contains("Spring Boot"));
                assertFalse(m.text().contains("React"));
            }
        }
        assertEquals(
                1, db.queryForObject("SELECT count(*) FROM brief_delivery_release", Integer.class));
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT count(*) FROM brief_delivery WHERE state='REJECTED'",
                        Integer.class));
    }

    @Test
    void retryKeepsSameRequest() {
        subscriber("a@example.test", "REACT");
        release(1, "REACT");
        gateway.retryFirst = true;
        service.prepareAndSend(CUT);
        gateway.sender = "changed@example.test";
        service.retry();
        assertEquals(2, gateway.messages.size());
        assertEquals(gateway.messages.get(0), gateway.messages.get(1));
        assertEquals("original@example.test", gateway.messages.get(1).sender());
        assertEquals("SENT", db.queryForObject("SELECT state FROM brief_delivery", String.class));
    }

    @Test
    void disablingTechnologyAfterDefiniteRejectionCancelsRetryAndReleasesAssignment() {
        subscriber("a@example.test", "REACT");
        release(1, "REACT");
        gateway.retryFirst = true;
        service.prepareAndSend(CUT);
        db.update("UPDATE brief_subscriber SET technology_enabled=false");
        service.retry();
        assertEquals(1, gateway.messages.size());
        assertEquals(
                "CANCELLED", db.queryForObject("SELECT state FROM brief_delivery", String.class));
        assertEquals(
                0, db.queryForObject("SELECT count(*) FROM brief_delivery_release", Integer.class));
    }

    @Test
    void changedSubscriptionGenerationHoldsAmbiguousDeliveryWithoutRepeating() {
        subscriber("a@example.test", "REACT");
        release(1, "REACT");
        gateway.unknownFirst = true;
        service.prepareAndSend(CUT);
        db.update("UPDATE brief_subscriber SET generation=generation+1");
        service.retry();
        assertEquals(1, gateway.messages.size());
        assertEquals("HELD", db.queryForObject("SELECT state FROM brief_delivery", String.class));
        assertFalse(service.hasRetryWork());
        assertEquals(
                1, db.queryForObject("SELECT count(*) FROM brief_delivery_release", Integer.class));
        UUID id = db.queryForObject("SELECT id FROM brief_delivery", UUID.class);
        assertTrue(service.resolveFromProviderEvidence(id, false, null));
        assertEquals(
                0, db.queryForObject("SELECT count(*) FROM brief_delivery_release", Integer.class));
    }

    static final class MutableClock extends Clock {
        final AtomicReference<Instant> now;

        MutableClock(Instant instant) {
            now = new AtomicReference<>(instant);
        }

        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        public Clock withZone(ZoneId z) {
            return this;
        }

        public Instant instant() {
            return now.get();
        }
    }

    static final class FakeGateway implements MailGateway {
        final List<Message> messages = Collections.synchronizedList(new ArrayList<>());
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        volatile boolean blockFirst, rejectFirst, retryFirst, unknownFirst;
        volatile String sender = "original@example.test";

        public String sender() {
            return sender;
        }

        public Duration idempotencyWindow() {
            return Duration.ofHours(24);
        }

        public Submission submit(Message message) {
            int n;
            synchronized (messages) {
                messages.add(message);
                n = messages.size();
            }
            if (n == 1 && blockFirst) {
                entered.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS))
                        throw new IllegalStateException("timeout");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return new Submission(Status.UNKNOWN, null);
            }
            if (n == 1 && unknownFirst) return new Submission(Status.UNKNOWN, null);
            if (n == 1 && rejectFirst) return new Submission(Status.PERMANENT_REJECTION, null);
            if (n == 1 && retryFirst) return new Submission(Status.RETRYABLE_REJECTION, null);
            return new Submission(Status.ACCEPTED, "fake-provider-id");
        }
    }
}
