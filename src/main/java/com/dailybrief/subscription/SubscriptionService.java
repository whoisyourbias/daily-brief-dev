package com.dailybrief.subscription;

import com.dailybrief.common.BriefProject;
import com.dailybrief.common.MailGateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

@Service
public class SubscriptionService {
    private final JdbcTemplate db;
    private final MailGateway mail;
    private final Clock clock;
    private final String base;
    private final SecureRandom random = new SecureRandom();

    public SubscriptionService(
            JdbcTemplate db,
            MailGateway mail,
            Clock clock,
            @Value("${dailybrief.public-base-url:http://localhost:8080}") String base) {
        this.db = db;
        this.mail = mail;
        this.clock = clock;
        this.base = base.replaceAll("/$", "");
    }

    public record Preferences(boolean news, boolean technology, Set<BriefProject> projects) {}

    public record Subscriber(
            long id, String status, boolean news, boolean technology, long generation) {}

    public record Confirmation(boolean news, boolean technology) {}

    public record ManagementSession(long subscriberId, long generation)
            implements java.io.Serializable {}

    private Instant now() {
        return clock.instant();
    }

    private java.sql.Timestamp timestamp(Instant i) {
        return java.sql.Timestamp.from(i);
    }

    private String digest(String input) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String token() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static boolean validEmail(String email) {
        return email != null
                && email.length() <= 254
                && email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    }

    public static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private Subscriber byEmail(String email, boolean lock) {
        var rows =
                db.query(
                        "SELECT id,status,news_enabled,technology_enabled,generation FROM"
                            + " brief_subscriber WHERE email=?"
                                + (lock ? " FOR UPDATE" : ""),
                        (rs, n) ->
                                new Subscriber(
                                        rs.getLong(1),
                                        rs.getString(2),
                                        rs.getBoolean(3),
                                        rs.getBoolean(4),
                                        rs.getLong(5)),
                        email);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Subscriber byId(long id, boolean lock) {
        var rows =
                db.query(
                        "SELECT id,status,news_enabled,technology_enabled,generation FROM"
                            + " brief_subscriber WHERE id=?"
                                + (lock ? " FOR UPDATE" : ""),
                        (rs, n) ->
                                new Subscriber(
                                        rs.getLong(1),
                                        rs.getString(2),
                                        rs.getBoolean(3),
                                        rs.getBoolean(4),
                                        rs.getLong(5)),
                        id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // Per-key upserts take row locks; sorting ensures concurrent requests lock in the same order.
    private boolean limit(String email, String ip) {
        Instant t = now();
        for (String[] key :
                List.of(new String[] {"address", digest(email)}, new String[] {"ip", digest(ip)})) {
            db.update(
                    "INSERT INTO brief_request_limit(kind,value,window_start,count) VALUES"
                        + " (?,?,?,0) ON CONFLICT (kind,value) DO NOTHING",
                    key[0],
                    key[1],
                    timestamp(t));
            db.queryForObject(
                    "SELECT count FROM brief_request_limit WHERE kind=? AND value=? FOR UPDATE",
                    Integer.class,
                    key[0],
                    key[1]);
        }
        var a =
                db.queryForMap(
                        "SELECT window_start,count FROM brief_request_limit WHERE kind='address'"
                            + " AND value=?",
                        digest(email));
        var i =
                db.queryForMap(
                        "SELECT window_start,count FROM brief_request_limit WHERE kind='ip' AND"
                            + " value=?",
                        digest(ip));
        boolean aExpired =
                ((java.sql.Timestamp) a.get("window_start"))
                                .toInstant()
                                .plusSeconds(900)
                                .compareTo(t)
                        <= 0;
        boolean iExpired =
                ((java.sql.Timestamp) i.get("window_start"))
                                .toInstant()
                                .plusSeconds(3600)
                                .compareTo(t)
                        <= 0;
        boolean allowed =
                (aExpired || ((Number) a.get("count")).intValue() < 1)
                        && (iExpired || ((Number) i.get("count")).intValue() < 10);
        // Count all valid requests by IP, even if the address limit was reached.
        db.update(
                "UPDATE brief_request_limit SET window_start=?,count=? WHERE kind='ip' AND value=?",
                timestamp(iExpired ? t : ((java.sql.Timestamp) i.get("window_start")).toInstant()),
                iExpired ? 1 : ((Number) i.get("count")).intValue() + 1,
                digest(ip));
        if (allowed)
            db.update(
                    "UPDATE brief_request_limit SET window_start=?,count=1 WHERE kind='address' AND"
                        + " value=?",
                    timestamp(t),
                    digest(email));
        return allowed;
    }

    private void selections(long id, Set<BriefProject> selected) {
        for (BriefProject p : BriefProject.values()) {
            if (selected.contains(p))
                db.update(
                        "INSERT INTO brief_project_selection(subscriber_id,project,selected_at)"
                            + " VALUES (?,?,?) ON CONFLICT DO NOTHING",
                        id,
                        p.name(),
                        timestamp(now()));
            else
                db.update(
                        "DELETE FROM brief_project_selection WHERE subscriber_id=? AND project=?",
                        id,
                        p.name());
        }
    }

    public Preferences preferences(long id, long generation) {
        Subscriber s = byId(id, false);
        if (s == null || !s.status().equals("ACTIVE") || s.generation() != generation) return null;
        Set<BriefProject> p = EnumSet.noneOf(BriefProject.class);
        db.query(
                "SELECT project FROM brief_project_selection WHERE subscriber_id=?",
                rs -> {
                    p.add(BriefProject.valueOf(rs.getString(1)));
                },
                id);
        return new Preferences(s.news(), s.technology(), p);
    }

    private MailGateway.Message issue(Subscriber s, String email, String purpose) {
        String token = token();
        db.update(
                "INSERT INTO"
                    + " brief_subscription_token(digest,subscriber_id,purpose,generation,expires_at)"
                    + " VALUES (?,?,?,?,?)",
                digest(token),
                s.id(),
                purpose,
                s.generation(),
                timestamp(now().plusSeconds(86400)));
        String link =
                base
                        + "/subscription/"
                        + (purpose.equals("CONFIRM") ? "confirm" : "manage")
                        + "/"
                        + token;
        String subject = purpose.equals("CONFIRM") ? "Daily Brief 구독 확인" : "Daily Brief 구독 관리";
        String body = "아래 링크를 열어 계속하세요.\n" + link;
        return new MailGateway.Message(
                UUID.randomUUID().toString(),
                email,
                subject,
                body,
                "<p>아래 링크를 열어 계속하세요.</p><p><a href=\"" + link + "\">계속하기</a></p>");
    }

    @Transactional
    public MailGateway.Message signup(String raw, Preferences p, String ip) {
        String email = normalize(raw);
        if (!limit(email, ip)) return null;
        db.update(
                "INSERT INTO brief_subscriber(email,status) VALUES (?, 'PENDING') ON"
                    + " CONFLICT(email) DO NOTHING",
                email);
        Subscriber s = byEmail(email, true);
        if (s.status().equals("ACTIVE")) return null;
        db.update(
                "UPDATE brief_subscriber SET"
                    + " status='PENDING',news_enabled=?,technology_enabled=?,generation=generation+1"
                    + " WHERE id=?",
                p.news(),
                p.technology(),
                s.id());
        selections(s.id(), p.projects());
        return issue(byId(s.id(), false), email, "CONFIRM");
    }

    @Transactional
    public MailGateway.Message renew(String raw, String purpose, String ip) {
        String email = normalize(raw);
        if (!limit(email, ip)) return null;
        Subscriber s = byEmail(email, true);
        if (s == null
                || (purpose.equals("CONFIRM") && !s.status().equals("PENDING"))
                || (purpose.equals("MANAGE") && !s.status().equals("ACTIVE"))) return null;
        if (purpose.equals("CONFIRM")) {
            db.update("UPDATE brief_subscriber SET generation=generation+1 WHERE id=?", s.id());
            s = byId(s.id(), false);
        }
        return issue(s, email, purpose);
    }

    public void send(MailGateway.Message message) {
        if (message == null) return;
        try {
            MailGateway.Submission result = mail.submit(message);
            org.slf4j.LoggerFactory.getLogger(SubscriptionService.class)
                    .info("Subscription mail submission status: {}", result.status());
        } catch (RuntimeException ex) {
            org.slf4j.LoggerFactory.getLogger(SubscriptionService.class)
                    .warn("Subscription mail submission failed");
        }
    }

    private record Token(long subscriberId, long generation) {}

    private Token valid(String raw, String purpose, boolean lock) {
        if (raw == null || raw.length() > 200) return null;
        var rows =
                db.query(
                        "SELECT subscriber_id,generation FROM brief_subscription_token WHERE"
                            + " digest=? AND purpose=? AND consumed_at IS NULL AND expires_at>?"
                                + (lock ? " FOR UPDATE" : ""),
                        (rs, n) -> new Token(rs.getLong(1), rs.getLong(2)),
                        digest(raw),
                        purpose,
                        timestamp(now()));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public boolean validLink(String raw, String purpose) {
        Token t = valid(raw, purpose, false);
        if (t == null) return false;
        Subscriber s = byId(t.subscriberId(), false);
        return s != null
                && s.generation() == t.generation()
                && s.status().equals(purpose.equals("CONFIRM") ? "PENDING" : "ACTIVE");
    }

    @Transactional
    public Confirmation confirm(String raw) {
        Token t = valid(raw, "CONFIRM", true);
        if (t == null) return null;
        Subscriber s = byId(t.subscriberId(), true);
        if (s == null || !s.status().equals("PENDING") || s.generation() != t.generation())
            return null;
        db.update(
                "UPDATE brief_subscription_token SET consumed_at=? WHERE digest=?",
                timestamp(now()),
                digest(raw));
        db.update("UPDATE brief_subscriber SET status='ACTIVE' WHERE id=?", s.id());
        db.update(
                "UPDATE brief_project_selection SET selected_at=? WHERE subscriber_id=?",
                timestamp(now()),
                s.id());
        return new Confirmation(s.news(), s.technology());
    }

    @Transactional
    public ManagementSession openManagement(String raw) {
        Token t = valid(raw, "MANAGE", true);
        if (t == null) return null;
        Subscriber s = byId(t.subscriberId(), true);
        if (s == null || !s.status().equals("ACTIVE") || s.generation() != t.generation())
            return null;
        db.update(
                "UPDATE brief_subscription_token SET consumed_at=? WHERE digest=?",
                timestamp(now()),
                digest(raw));
        return new ManagementSession(s.id(), s.generation());
    }

    @Transactional
    public boolean save(long id, long generation, Preferences p) {
        Subscriber s = byId(id, true);
        if (s == null || !s.status().equals("ACTIVE") || s.generation() != generation) return false;
        db.update(
                "UPDATE brief_subscriber SET news_enabled=?,technology_enabled=? WHERE id=?",
                p.news(),
                p.technology(),
                id);
        selections(id, p.projects());
        return true;
    }

    @Transactional
    public void unsubscribe(long id, long generation) {
        db.update(
                "UPDATE brief_subscriber SET status='UNSUBSCRIBED',generation=generation+1 WHERE"
                    + " id=? AND generation=? AND status='ACTIVE'",
                id,
                generation);
    }
}
