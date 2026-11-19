package com.dailybrief.collection;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

class CollectionPostgresTest {
    PostgreSQLContainer<?> container;
    HttpServer server;
    JdbcTemplate admin;
    String schema;

    @AfterEach
    void cleanup() {
        if (server != null) server.stop(0);
        if (admin != null && schema != null)
            admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        if (container != null) container.stop();
    }

    @Test
    void keepsInitialFloorAndAtomicallyRetriesAllPagesIncludingLateItems() throws Exception {
        String url = System.getenv("DAILYBRIEF_TEST_DB_URL"),
                user = System.getenv("DAILYBRIEF_TEST_DB_USER"),
                password = System.getenv("DAILYBRIEF_TEST_DB_PASSWORD");
        if (url == null || url.isBlank()) {
            Assumptions.assumeTrue(
                    DockerClientFactory.instance().isDockerAvailable(),
                    "PostgreSQL test needs DAILYBRIEF_TEST_DB_URL or Docker");
            container = new PostgreSQLContainer<>("postgres:16-alpine");
            container.start();
            url = container.getJdbcUrl();
            user = container.getUsername();
            password = container.getPassword();
        }
        DriverManagerDataSource root = new DriverManagerDataSource(url, user, password);
        admin = new JdbcTemplate(root);
        schema = "collection_test_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        String scopedUrl = url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        DataSource data = new DriverManagerDataSource(scopedUrl, user, password);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V3__collection.sql"))
                .execute(data);
        JdbcTemplate db = new JdbcTemplate(data);
        AtomicInteger run = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    String path = exchange.getRequestURI().toString();
                    int status = 200;
                    String body = "[]";
                    if (path.equals("/feed")) body = "<feed xmlns='http://www.w3.org/2005/Atom'/>";
                    else if (path.contains("/releases?")) {
                        if (path.endsWith("page=2")) {
                            if (run.get() == 0) status = 500;
                        } else if (path.contains("facebook/react")) {
                            String base =
                                    "{\"id\":101,\"tag_name\":\"v1\",\"html_url\":\"https://example.org/v1\",\"published_at\":\"2026-11-19T00:00:00Z\",\"draft\":false,\"prerelease\":false}";
                            String late =
                                    "{\"id\":102,\"tag_name\":\"v2\",\"html_url\":\"https://example.org/v2\",\"published_at\":\"2026-11-19T01:00:00Z\",\"draft\":false,\"prerelease\":false}";
                            String old =
                                    "{\"id\":103,\"tag_name\":\"old\",\"html_url\":\"https://example.org/old\",\"published_at\":\"2026-11-17T00:00:00Z\",\"draft\":false,\"prerelease\":false}";
                            body =
                                    "["
                                            + base
                                            + (run.get() >= 2 ? "," + late + "," + old : "")
                                            + "]";
                        }
                    }
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(status, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        server.start();
        String local = "http://127.0.0.1:" + server.getAddress().getPort();
        CollectionService service =
                new CollectionService(
                        db,
                        new TransactionTemplate(new DataSourceTransactionManager(data)),
                        new ObjectMapper(),
                        local,
                        local + "/feed",
                        local,
                        local,
                        "",
                        "unused");
        Instant first = Instant.parse("2026-11-19T23:00:00Z");
        service.collect(first);
        assertEquals(0, db.queryForObject("SELECT count(*) FROM brief_release", Integer.class));
        assertEquals(
                first.minus(Duration.ofHours(24)),
                db.queryForObject(
                        "SELECT eligible_floor FROM brief_collection_repo WHERE project='REACT'",
                        (rs, n) -> rs.getTimestamp(1).toInstant()));
        assertNull(
                db.queryForObject(
                        "SELECT completed_cutoff FROM brief_collection_repo WHERE project='REACT'",
                        (rs, n) -> rs.getTimestamp(1)));
        run.set(1);
        service.collect(first.plus(Duration.ofDays(1)));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM brief_release", Integer.class));
        assertEquals(
                first.plus(Duration.ofDays(1)),
                db.queryForObject(
                        "SELECT completed_cutoff FROM brief_collection_repo WHERE project='REACT'",
                        (rs, n) -> rs.getTimestamp(1).toInstant()));
        run.set(2);
        service.collect(first.plus(Duration.ofDays(2)));
        service.collect(first.plus(Duration.ofDays(2)));
        assertEquals(2, db.queryForObject("SELECT count(*) FROM brief_release", Integer.class));
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT count(*) FROM brief_release WHERE id=103", Integer.class));
        assertEquals(
                first.minus(Duration.ofHours(24)),
                db.queryForObject(
                        "SELECT eligible_floor FROM brief_collection_repo WHERE project='REACT'",
                        (rs, n) -> rs.getTimestamp(1).toInstant()));
    }
}
