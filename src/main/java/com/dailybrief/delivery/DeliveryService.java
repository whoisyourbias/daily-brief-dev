package com.dailybrief.delivery;

import com.dailybrief.common.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

@Service
public class DeliveryService {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final MailGateway gateway;
    private final Clock clock;

    public DeliveryService(
            JdbcTemplate db, PlatformTransactionManager manager, MailGateway gateway, Clock clock) {
        this.db = db;
        this.tx = new TransactionTemplate(manager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.gateway = gateway;
        this.clock = clock;
    }

    private record Item(
            String title,
            String url,
            String detail,
            Long releaseId,
            String project,
            Instant selectedAt) {}

    private record Work(
            UUID id,
            long owner,
            String recipient,
            String subject,
            String text,
            String html,
            String sender) {}

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void prepareAndSend(Instant cutoff) {
        List<Item> news =
                db.query(
                        "SELECT source,title,url FROM brief_news WHERE cutoff=? ORDER BY rank",
                        (row, index) ->
                                new Item(
                                        row.getString("title"),
                                        row.getString("url"),
                                        sourceName(row.getString("source")),
                                        null,
                                        null,
                                        null),
                        Timestamp.from(cutoff));
        var ids =
                db.queryForList(
                        "SELECT id FROM brief_subscriber WHERE status='ACTIVE'", Long.class);
        for (long id : ids)
            for (MailKind kind : MailKind.values()) {
                UUID delivery = tx.execute(s -> freeze(id, cutoff, kind, news));
                if (delivery != null) send(delivery);
            }
    }

    private UUID freeze(long subscriber, Instant cutoff, MailKind kind, List<Item> news) {
        var subscribers =
                db.queryForList(
                        "SELECT email, status, news_enabled, technology_enabled, generation FROM"
                            + " brief_subscriber WHERE id=? FOR UPDATE",
                        subscriber);
        if (subscribers.isEmpty()) return null;
        var sub = subscribers.get(0);
        if (!"ACTIVE".equals(sub.get("status"))
                || !Boolean.TRUE.equals(
                        sub.get(kind == MailKind.NEWS ? "news_enabled" : "technology_enabled")))
            return null;
        if (Boolean.TRUE.equals(
                db.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM brief_delivery WHERE subscriber_id=? AND"
                            + " cutoff=? AND kind=?)",
                        Boolean.class,
                        subscriber,
                        Timestamp.from(cutoff),
                        kind.name()))) return null;
        List<Item> items;
        if (kind == MailKind.NEWS) {
            items = news;
        } else {
            items =
                    db.query(
                            "SELECT r.id,r.project,r.version,r.url,r.summary,s.selected_at FROM"
                                + " brief_release r JOIN brief_project_selection s ON"
                                + " s.project=r.project AND s.subscriber_id=? WHERE r.published_at"
                                + " < ? AND r.published_at >= s.selected_at AND NOT EXISTS (SELECT"
                                + " 1 FROM brief_delivery_release a WHERE a.subscriber_id=? AND"
                                + " a.release_id=r.id) ORDER BY r.published_at,r.id",
                            (r, n) ->
                                    new Item(
                                            BriefProject.valueOf(r.getString("project"))
                                                            .displayName()
                                                    + " "
                                                    + r.getString("version"),
                                            r.getString("url"),
                                            r.getString("summary"),
                                            r.getLong("id"),
                                            r.getString("project"),
                                            r.getTimestamp("selected_at").toInstant()),
                            subscriber,
                            Timestamp.from(cutoff),
                            subscriber);
        }
        items = items.stream().filter(i -> safe(i.url())).toList();
        if (items.isEmpty()) return null;
        StringBuilder text = new StringBuilder(),
                html = new StringBuilder("<!doctype html><html><body><main>");
        for (Item i : items) {
            text.append(i.title()).append("\n").append(i.url()).append("\n");
            html.append("<p><a style=\"color:#075e59;text-decoration:underline\" href=\"")
                    .append(escape(i.url()))
                    .append("\">")
                    .append(escape(i.title()))
                    .append("</a>");
            if (i.detail() != null && !i.detail().isBlank()) {
                text.append(i.detail()).append("\n");
                html.append("<br>").append(escape(i.detail()));
            }
            text.append("\n");
            html.append("</p>");
        }
        html.append("</main></body></html>");
        UUID id = UUID.randomUUID();
        db.update(
                """
INSERT INTO brief_delivery
  (id, subscriber_id, cutoff, subscriber_generation, kind, recipient, sender, subject, text_body, html_body)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
""",
                id,
                subscriber,
                Timestamp.from(cutoff),
                sub.get("generation"),
                kind.name(),
                sub.get("email"),
                gateway.sender(),
                kind.subject(),
                text.toString(),
                html.toString());
        for (Item i : items)
            if (i.releaseId() != null)
                db.update(
                        "INSERT INTO"
                            + " brief_delivery_release(delivery_id,subscriber_id,release_id,project,selected_at)"
                            + " VALUES (?,?,?,?,?)",
                        id,
                        subscriber,
                        i.releaseId(),
                        i.project(),
                        Timestamp.from(i.selectedAt()));
        return id;
    }

    public boolean hasRetryWork() {
        return Boolean.TRUE.equals(
                db.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM brief_delivery WHERE state IN"
                            + " ('READY','RETRY','UNKNOWN') AND (lease_until IS NULL OR lease_until"
                            + " <= ?))",
                        Boolean.class,
                        Timestamp.from(clock.instant())));
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void retry() {
        for (UUID id :
                db.queryForList(
                        "SELECT id FROM brief_delivery WHERE state IN ('READY','RETRY','UNKNOWN')"
                            + " AND (lease_until IS NULL OR lease_until <= ?)",
                        UUID.class,
                        Timestamp.from(clock.instant()))) send(id);
    }

    private void send(UUID id) {
        Work w = tx.execute(s -> claim(id));
        if (w == null) return;
        // The request can leave the process only after the guarded submitted marker commits.
        MailGateway.Submission result =
                gateway.submit(
                        new MailGateway.Message(
                                w.id().toString(),
                                w.recipient(),
                                w.subject(),
                                w.text(),
                                w.html(),
                                w.sender()));
        tx.executeWithoutResult(s -> finish(w, result));
    }

    private Work claim(UUID id) {
        Instant now = clock.instant();
        var rows = db.queryForList("SELECT * FROM brief_delivery WHERE id=? FOR UPDATE", id);
        if (rows.isEmpty()) return null;
        var d = rows.get(0);
        String state = (String) d.get("state");
        if (!List.of("READY", "RETRY", "UNKNOWN").contains(state)) return null;
        Timestamp lease = (Timestamp) d.get("lease_until");
        if (lease != null && lease.toInstant().isAfter(now)) return null;
        long owner = ((Number) d.get("owner")).longValue() + 1;
        db.update(
                "UPDATE brief_delivery SET owner=?,lease_until=?, uncertain=uncertain OR"
                    + " (state='UNKNOWN' AND lease_until IS NOT NULL) WHERE id=?",
                owner,
                Timestamp.from(now.plusSeconds(120)),
                id);
        Long subscriber = ((Number) d.get("subscriber_id")).longValue();
        var sub =
                db.queryForList(
                        "SELECT status,news_enabled,technology_enabled,generation FROM"
                            + " brief_subscriber WHERE id=? FOR UPDATE",
                        subscriber);
        boolean allowed =
                !sub.isEmpty()
                        && "ACTIVE".equals(sub.get(0).get("status"))
                        && Boolean.TRUE.equals(
                                sub.get(0)
                                        .get(
                                                "NEWS".equals(d.get("kind"))
                                                        ? "news_enabled"
                                                        : "technology_enabled"))
                        && Objects.equals(
                                ((Number) sub.get(0).get("generation")).longValue(),
                                ((Number) d.get("subscriber_generation")).longValue());
        Boolean invalid =
                db.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM brief_delivery_release a LEFT JOIN"
                            + " brief_project_selection p ON p.subscriber_id=a.subscriber_id AND"
                            + " p.project=a.project AND p.selected_at=a.selected_at WHERE"
                            + " a.delivery_id=? AND p.subscriber_id IS NULL)",
                        Boolean.class,
                        id);
        Timestamp first = (Timestamp) d.get("first_submitted_at");
        if (!allowed || Boolean.TRUE.equals(invalid)) {
            if (first == null
                    || ("RETRY".equals(state) && !Boolean.TRUE.equals(d.get("uncertain"))))
                close(id, owner, "CANCELLED");
            else
                db.update(
                        "UPDATE brief_delivery SET state='HELD',lease_until=NULL WHERE id=? AND"
                            + " owner=? AND lease_until>?",
                        id,
                        owner,
                        Timestamp.from(clock.instant()));
            return null;
        }
        // Leave enough time for the provider HTTP timeout before its idempotency guarantee expires.
        Instant latestRetry =
                first == null
                        ? null
                        : first.toInstant().plus(gateway.idempotencyWindow()).minusSeconds(20);
        if (latestRetry != null && !now.isBefore(latestRetry)) {
            if ("RETRY".equals(state) && !Boolean.TRUE.equals(d.get("uncertain")))
                close(id, owner, "EXPIRED");
            else
                db.update(
                        "UPDATE brief_delivery SET state='HELD',lease_until=NULL WHERE id=? AND"
                            + " owner=? AND lease_until>?",
                        id,
                        owner,
                        Timestamp.from(clock.instant()));
            return null;
        }
        int updated =
                db.update(
                        "UPDATE brief_delivery SET"
                            + " first_submitted_at=COALESCE(first_submitted_at,?),last_submitted_at=?,state='UNKNOWN'"
                            + " WHERE id=? AND owner=? AND lease_until>?",
                        Timestamp.from(now),
                        Timestamp.from(now),
                        id,
                        owner,
                        Timestamp.from(now));
        if (updated != 1) return null;
        return new Work(
                id,
                owner,
                (String) d.get("recipient"),
                (String) d.get("subject"),
                (String) d.get("text_body"),
                (String) d.get("html_body"),
                (String) d.get("sender"));
    }

    private void finish(Work w, MailGateway.Submission result) {
        String state =
                switch (result.status()) {
                    case ACCEPTED -> "SENT";
                    case RETRYABLE_REJECTION -> "RETRY";
                    case PERMANENT_REJECTION -> "REJECTED";
                    case UNKNOWN -> "UNKNOWN";
                };
        int updated =
                db.update(
                        "UPDATE brief_delivery SET state=CASE WHEN uncertain AND ? IN"
                            + " ('RETRY','REJECTED') THEN 'UNKNOWN' ELSE ? END, uncertain=uncertain"
                            + " OR ?='UNKNOWN',last_result=?,provider_id=?,lease_until=NULL WHERE"
                            + " id=? AND owner=? AND lease_until>?",
                        state,
                        state,
                        state,
                        result.status().name(),
                        result.providerId(),
                        w.id(),
                        w.owner(),
                        Timestamp.from(clock.instant()));
        if (updated == 1 && state.equals("REJECTED"))
            db.update(
                    "DELETE FROM brief_delivery_release WHERE delivery_id=? AND EXISTS (SELECT 1"
                        + " FROM brief_delivery WHERE id=? AND owner=? AND state='REJECTED')",
                    w.id(),
                    w.id(),
                    w.owner());
    }

    private void close(UUID id, long owner, String state) {
        int updated =
                db.update(
                        "UPDATE brief_delivery SET state=?,lease_until=NULL WHERE id=? AND owner=?"
                            + " AND lease_until>?",
                        state,
                        id,
                        owner,
                        Timestamp.from(clock.instant()));
        if (updated == 1) db.update("DELETE FROM brief_delivery_release WHERE delivery_id=?", id);
    }

    /**
     * Invoke only from an authenticated operator workflow after independently checking provider
     * records.
     */
    public boolean resolveFromProviderEvidence(UUID id, boolean accepted, String providerId) {
        return Boolean.TRUE.equals(
                tx.execute(
                        s -> {
                            var rows =
                                    db.queryForList(
                                            "SELECT owner,state,lease_until FROM brief_delivery"
                                                + " WHERE id=? FOR UPDATE",
                                            id);
                            if (rows.isEmpty()
                                    || !List.of("UNKNOWN", "HELD")
                                            .contains(rows.get(0).get("state"))) return false;
                            Timestamp lease = (Timestamp) rows.get(0).get("lease_until");
                            if (!accepted
                                    && lease != null
                                    && lease.toInstant().isAfter(clock.instant())) return false;
                            long owner = ((Number) rows.get(0).get("owner")).longValue() + 1;
                            db.update(
                                    "UPDATE brief_delivery SET"
                                        + " owner=?,lease_until=NULL,state=?,provider_id=? WHERE"
                                        + " id=?",
                                    owner,
                                    accepted ? "SENT" : "REJECTED",
                                    providerId,
                                    id);
                            if (!accepted)
                                db.update(
                                        "DELETE FROM brief_delivery_release WHERE delivery_id=? AND"
                                            + " EXISTS (SELECT 1 FROM brief_delivery WHERE id=? AND"
                                            + " owner=?)",
                                        id,
                                        id,
                                        owner);
                            return true;
                        }));
    }

    private static String sourceName(String source) {
        return switch (source) {
            case "HACKER_NEWS" -> "Hacker News";
            case "GEEK_NEWS" -> "GeekNews";
            default -> source;
        };
    }

    private static boolean safe(String value) {
        try {
            URI u = URI.create(value);
            return ("http".equalsIgnoreCase(u.getScheme())
                            || "https".equalsIgnoreCase(u.getScheme()))
                    && u.getHost() != null
                    && u.getUserInfo() == null;
        } catch (Exception e) {
            return false;
        }
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
