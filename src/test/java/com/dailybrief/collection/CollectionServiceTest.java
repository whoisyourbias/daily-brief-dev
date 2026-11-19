package com.dailybrief.collection;

import static org.junit.jupiter.api.Assertions.*;

import com.dailybrief.common.BriefProject;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

class CollectionServiceTest {
    private HttpServer server;

    private String start(java.util.function.Function<String, String> responder) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    String body = responder.apply(exchange.getRequestURI().toString());
                    int status = body == null ? 500 : 200;
                    byte[] bytes = (body == null ? "error" : body).getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(status, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private CollectionService service(String url) {
        return new CollectionService(
                null, null, new ObjectMapper(), url, url, url, url, "", "model");
    }

    @Test
    void windowIsInclusiveAtStartAndExclusiveAtEnd() {
        Instant end = Instant.parse("2026-11-19T23:00:00Z"), start = end.minusSeconds(86400);
        assertTrue(CollectionService.inWindow(start, start, end));
        assertFalse(CollectionService.inWindow(end, start, end));
        assertFalse(CollectionService.inWindow(start.minusSeconds(1), start, end));
        assertFalse(CollectionService.validUrl("javascript:alert(1)"));
        assertFalse(CollectionService.validUrl("https://user@example.com"));
    }

    @Test
    void hackerNewsRetainsRankingAndFiltersBoundary() throws Exception {
        Instant end = Instant.parse("2026-11-19T23:00:00Z");
        long t = end.getEpochSecond();
        String url =
                start(
                        path -> {
                            if (path.equals("/topstories.json")) return "[1,2,3]";
                            if (path.contains("/1."))
                                return "{\"type\":\"story\",\"title\":\"end\",\"url\":\"https://example.com/end\",\"time\":"
                                        + t
                                        + "}";
                            if (path.contains("/2."))
                                return "{\"type\":\"story\",\"title\":\"first\",\"url\":\"https://example.com/first\",\"time\":"
                                        + (t - 86400)
                                        + "}";
                            return "{\"type\":\"story\",\"title\":\"second\",\"url\":\"https://example.com/second\",\"time\":"
                                    + (t - 10)
                                    + "}";
                        });
        var method = CollectionService.class.getDeclaredMethod("fetchHn", Instant.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<CollectionService.News> rows =
                (List<CollectionService.News>) method.invoke(service(url), end);
        assertEquals(
                List.of("first", "second"),
                rows.stream().map(CollectionService.News::title).toList());
    }

    @Test
    void scansBeyondTheFirstHundredRankedItems() throws Exception {
        String ids =
                java.util.stream.IntStream.rangeClosed(1, 102)
                        .mapToObj(Integer::toString)
                        .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        String url =
                start(
                        path -> {
                            if (path.equals("/topstories.json")) return ids;
                            if (path.contains("/102.json"))
                                return "{\"type\":\"story\",\"title\":\"rank"
                                           + " 102\",\"url\":\"https://example.com/102\",\"time\":1795129199}";
                            return "{\"type\":\"job\"}";
                        });
        var method = CollectionService.class.getDeclaredMethod("fetchHn", Instant.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<CollectionService.News> rows =
                (List<CollectionService.News>)
                        method.invoke(service(url), Instant.parse("2026-11-19T23:00:00Z"));
        assertEquals(
                List.of("rank 102"), rows.stream().map(CollectionService.News::title).toList());
    }

    @Test
    void geekAtomSortsByPublishedAndRetainsTopic() throws Exception {
        String url =
                start(
                        path ->
                                "<feed"
                                    + " xmlns='http://www.w3.org/2005/Atom'><entry><title>older</title><published>2026-11-19T01:00:00Z</published><link"
                                    + " href='https://news.hada.io/topic?id=1'/></entry><entry><title>newer</title><published>2026-11-19T02:00:00Z</published><link"
                                    + " href='https://news.hada.io/topic?id=2'/><link"
                                    + " href='https://example.com/article'/></entry></feed>");
        var method = CollectionService.class.getDeclaredMethod("fetchGeek", Instant.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<CollectionService.News> rows =
                (List<CollectionService.News>)
                        method.invoke(service(url), Instant.parse("2026-11-19T23:00:00Z"));
        assertEquals(
                List.of("newer", "older"),
                rows.stream().map(CollectionService.News::title).toList());
        assertEquals("https://example.com/article", rows.get(0).identity());
        assertEquals("https://news.hada.io/topic?id=2", rows.get(0).url());
    }

    @Test
    void releasePageFailureAbortsWholeFetch() throws Exception {
        String url =
                start(
                        path ->
                                path.endsWith("page=1")
                                        ? "[{\"id\":1,\"tag_name\":\"v1\",\"html_url\":\"https://example.com/v1\",\"published_at\":\"2026-11-19T01:00:00Z\",\"draft\":false,\"prerelease\":false}]"
                                        : null);
        var method =
                CollectionService.class.getDeclaredMethod(
                        "fetchReleases", BriefProject.class, Instant.class, Instant.class);
        method.setAccessible(true);
        assertThrows(
                java.lang.reflect.InvocationTargetException.class,
                () ->
                        method.invoke(
                                service(url),
                                BriefProject.REACT,
                                Instant.parse("2026-11-18T23:00:00Z"),
                                Instant.parse("2026-11-19T23:00:00Z")));
    }
}
