package com.dailybrief.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.dailybrief.common.MailGateway;
import com.dailybrief.common.MailKind;
import com.dailybrief.WebLambdaHandler;
import com.amazonaws.services.lambda.runtime.Context;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Instant;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Pattern;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = "server.servlet.session.cookie.secure=true")
@AutoConfigureMockMvc
class SubscriptionPostgresTest {
    @Container
    static PostgreSQLContainer<?> dbContainer = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", dbContainer::getJdbcUrl);
        r.add("spring.datasource.username", dbContainer::getUsername);
        r.add("spring.datasource.password", dbContainer::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @MockitoBean MailGateway gateway;
    @MockitoBean Clock clock;

    private static final Instant START = Instant.parse("2026-11-23T00:00:00Z");
    private static final String MANAGEMENT_COOKIE = "__Host-brief-management";

    @BeforeEach
    void clean() {
        db.execute(
                "TRUNCATE brief_subscription_token, brief_project_selection, brief_subscriber,"
                    + " brief_request_limit CASCADE");
        reset(gateway);
        when(clock.instant()).thenReturn(START);
        when(gateway.submit(any()))
                .thenAnswer(
                        invocation -> {
                            assertThat(
                                            org.springframework.transaction.support
                                                    .TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            return new MailGateway.Submission(MailGateway.Status.ACCEPTED, "ok");
                        });
    }

    @Test
    void invalidRequestsHaveNoEffectsAndCsrfIsRequired() throws Exception {
        mvc.perform(postWithCsrf("/subscription").param("email", "a@example.com"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("메일을 하나 이상 선택")));
        mvc.perform(
                        postWithCsrf("/subscription")
                                .param("email", "a@example.com")
                                .param("technology", "true"))
                .andExpect(status().isOk())
                .andExpect(
                        content().string(org.hamcrest.Matchers.containsString("프로젝트를 하나 이상 선택")));
        mvc.perform(post("/subscription").param("email", "a@example.com").param("news", "true"))
                .andExpect(status().isForbidden());
        assertThat(db.queryForObject("SELECT count(*) FROM brief_subscriber", Integer.class))
                .isZero();
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM brief_subscription_token", Integer.class))
                .isZero();
        verifyNoInteractions(gateway);
    }

    @Test
    void expiredLinkRenewalPreservesSelectionsAndConfirmationIsSingleUse() throws Exception {
        mvc.perform(
                        postWithCsrf("/subscription")
                                .param("email", "a@example.com")
                                .param("technology", "true")
                                .param("projects", "REACT"))
                .andExpect(status().isOk());
        ArgumentCaptor<MailGateway.Message> messages =
                ArgumentCaptor.forClass(MailGateway.Message.class);
        verify(gateway).submit(messages.capture());
        String old =
                messages.getValue()
                        .text()
                        .substring(messages.getValue().text().lastIndexOf('/') + 1)
                        .trim();
        when(clock.instant()).thenReturn(START.plusSeconds(86_401));
        mvc.perform(get("/subscription/confirm/" + old))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("링크 다시 요청")));
        assertThat(db.queryForObject("SELECT status FROM brief_subscriber", String.class))
                .isEqualTo("PENDING");
        mvc.perform(
                        postWithCsrf("/subscription/confirm/" + old + "/renew")
                                .param("email", "a@example.com"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("요청을 접수했습니다")));
        verify(gateway, times(2)).submit(messages.capture());
        var all = messages.getAllValues();
        var last = all.get(all.size() - 1);
        String fresh = last.text().substring(last.text().lastIndexOf('/') + 1).trim();
        assertThat(db.queryForObject("SELECT project FROM brief_project_selection", String.class))
                .isEqualTo("REACT");
        mvc.perform(post("/subscription/confirm/" + fresh)).andExpect(status().isForbidden());
        mvc.perform(postWithCsrf("/subscription/confirm/" + fresh))
                .andExpect(status().isOk())
                .andExpect(
                        content()
                                .string(
                                        org.hamcrest.Matchers.containsString(
                                                MailKind.TECHNOLOGY.subject())))
                .andExpect(
                        content()
                                .string(
                                        org.hamcrest.Matchers.not(
                                                org.hamcrest.Matchers.containsString(
                                                        MailKind.NEWS.subject()))));
        assertThat(db.queryForObject("SELECT status FROM brief_subscriber", String.class))
                .isEqualTo("ACTIVE");
        mvc.perform(postWithCsrf("/subscription/confirm/" + fresh))
                .andExpect(status().isOk())
                .andExpect(
                        content()
                                .string(
                                        org.hamcrest.Matchers.not(
                                                org.hamcrest.Matchers.containsString(
                                                        "구독이 완료되었습니다"))));
    }

    @Test
    void oldManagementSessionCannotChangeResubscribedAddress() throws Exception {
        db.update(
                "INSERT INTO brief_subscriber(email,status,news_enabled,generation) VALUES"
                    + " ('a@example.com','ACTIVE',true,1)");
        mvc.perform(
                        postWithCsrf("/subscription/request-management")
                                .param("email", "a@example.com"))
                .andExpect(status().isOk());
        ArgumentCaptor<MailGateway.Message> messages =
                ArgumentCaptor.forClass(MailGateway.Message.class);
        verify(gateway).submit(messages.capture());
        String token =
                messages.getValue()
                        .text()
                        .substring(messages.getValue().text().lastIndexOf('/') + 1)
                        .trim();
        var result =
                mvc.perform(postWithCsrf("/subscription/manage/" + token))
                        .andExpect(status().is3xxRedirection())
                        .andReturn();
        Cookie session = result.getResponse().getCookie(MANAGEMENT_COOKIE);
        assertThat(result.getRequest().getSession(false)).isNull();
        String csrf = managementCsrf(session);
        db.update("UPDATE brief_subscriber SET generation=3, status='ACTIVE', news_enabled=true");
        mvc.perform(post("/subscription/settings").cookie(session).param("_csrf", csrf))
                .andExpect(status().is3xxRedirection());
        assertThat(db.queryForObject("SELECT news_enabled FROM brief_subscriber", Boolean.class))
                .isTrue();
        mvc.perform(post("/subscription/unsubscribe").cookie(session).param("_csrf", csrf))
                .andExpect(status().isOk());
        assertThat(db.queryForObject("SELECT status FROM brief_subscriber", String.class))
                .isEqualTo("ACTIVE");
    }

    @Test
    void cookieSessionEnforcesCsrfRotationAndSlidingExpiry() throws Exception {
        db.update("INSERT INTO brief_subscriber(email,status) VALUES ('a@example.com','ACTIVE')");
        var anonymousPage = mvc.perform(get("/subscription/request-management"))
                .andExpect(status().isOk()).andReturn();
        Cookie anonymousCookie = anonymousPage.getResponse().getCookie("__Host-brief-csrf");
        String anonymousCsrf = ((CsrfToken) anonymousPage.getRequest()
                .getAttribute(CsrfToken.class.getName())).getToken();
        mvc.perform(post("/subscription/request-management").cookie(anonymousCookie)
                        .param("_csrf", anonymousCsrf).param("email", "a@example.com"))
                .andExpect(status().isOk());
        var sent = ArgumentCaptor.forClass(MailGateway.Message.class);
        verify(gateway).submit(sent.capture());
        String link = sent.getValue().text().trim().replaceFirst("(?s).*https?://[^/]+", "");
        var first = mvc.perform(post(link).cookie(anonymousCookie).param("_csrf", anonymousCsrf))
                .andExpect(redirectedUrl("/subscription/settings")).andReturn();
        Cookie session = first.getResponse().getCookie(MANAGEMENT_COOKIE);
        assertThat(session.isHttpOnly()).isTrue();
        assertThat(session.getSecure()).isTrue();
        assertThat(first.getResponse().getHeader("Set-Cookie")).contains("SameSite=Lax");
        assertThat(db.queryForObject("SELECT digest FROM brief_management_session", String.class))
                .isNotEqualTo(session.getValue());
        assertThat(first.getRequest().getSession(false)).isNull();

        when(clock.instant()).thenReturn(START.plusSeconds(899));
        String sessionCsrf = managementCsrf(session);
        mvc.perform(post("/subscription/settings").cookie(session, anonymousCookie)
                        .param("_csrf", anonymousCsrf).param("news", "true"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/subscription/settings").cookie(session)
                        .param("_csrf", sessionCsrf).param("news", "true"))
                .andExpect(status().isOk());
        assertThat(db.queryForObject("SELECT news_enabled FROM brief_subscriber", Boolean.class))
                .isTrue();

        when(clock.instant()).thenReturn(START.plusSeconds(900));
        mvc.perform(post("/subscription/request-management").cookie(session)
                        .param("_csrf", sessionCsrf).param("email", "a@example.com"))
                .andExpect(status().isOk());
        verify(gateway, times(2)).submit(sent.capture());
        String freshLink = sent.getValue().text().trim().replaceFirst("(?s).*https?://[^/]+", "");
        var rotated = mvc.perform(post(freshLink).cookie(session).param("_csrf", sessionCsrf))
                .andExpect(redirectedUrl("/subscription/settings")).andReturn();
        Cookie fresh = rotated.getResponse().getCookie(MANAGEMENT_COOKIE);
        mvc.perform(get("/subscription/settings").cookie(session))
                .andExpect(redirectedUrl("/subscription/request-management"));
        String freshCsrf = managementCsrf(fresh);
        assertThat(freshCsrf).isNotEqualTo(sessionCsrf);
        mvc.perform(post("/subscription/settings").cookie(fresh).param("_csrf", sessionCsrf))
                .andExpect(status().isForbidden());
        db.execute("ALTER TABLE brief_management_session RENAME TO unavailable_management_session");
        try {
            mvc.perform(get("/subscription/settings").cookie(fresh))
                    .andExpect(status().isServiceUnavailable());
            mvc.perform(post("/subscription/settings").cookie(fresh).param("_csrf", freshCsrf))
                    .andExpect(status().isServiceUnavailable());
        } finally {
            db.execute("ALTER TABLE unavailable_management_session RENAME TO brief_management_session");
        }
        when(clock.instant()).thenReturn(START.plusSeconds(1800));
        mvc.perform(get("/subscription/settings").cookie(fresh))
                .andExpect(redirectedUrl("/subscription/request-management"));
    }

    private MockHttpServletRequestBuilder postWithCsrf(String path) throws Exception {
        var page = mvc.perform(get("/")).andExpect(status().isOk()).andReturn();
        var cookie = page.getResponse().getCookie("__Host-brief-csrf");
        var csrf = (CsrfToken) page.getRequest().getAttribute(CsrfToken.class.getName());
        return post(path).cookie(cookie).param(csrf.getParameterName(), csrf.getToken());
    }

    private String managementCsrf(Cookie cookie) throws Exception {
        var settings = mvc.perform(get("/subscription/settings").cookie(cookie))
                .andExpect(status().isOk()).andReturn();
        assertThat(settings.getRequest().getSession(false)).isNull();
        return ((CsrfToken) settings.getRequest().getAttribute(CsrfToken.class.getName())).getToken();
    }

    @Test
    void lambdaReadsSessionFromAnotherWebEnvironmentAndUsesGatewayIp() throws Exception {
        db.update("INSERT INTO brief_subscriber(email,status) VALUES ('a@example.com','ACTIVE')");
        mvc.perform(postWithCsrf("/subscription/request-management").param("email", "a@example.com"))
                .andExpect(status().isOk());
        var sent = ArgumentCaptor.forClass(MailGateway.Message.class);
        verify(gateway).submit(sent.capture());
        String link = sent.getValue().text().trim().replaceFirst("(?s).*https?://[^/]+", "");
        Cookie session = mvc.perform(postWithCsrf(link)).andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getCookie(MANAGEMENT_COOKIE);
        db.update("UPDATE brief_management_session SET expires_at=now()+interval '15 minutes'");

        var settings = Map.of("spring.datasource.url", dbContainer.getJdbcUrl(),
                "spring.datasource.username", dbContainer.getUsername(),
                "spring.datasource.password", dbContainer.getPassword(),
                "spring.datasource.driver-class-name", "org.postgresql.Driver",
                "RESEND_API_KEY", "");
        var previous = new HashMap<String, String>();
        settings.forEach((key, value) -> previous.put(key, System.setProperty(key, value)));
        WebLambdaHandler lambda;
        try {
            lambda = new WebLambdaHandler();
        } finally {
            previous.forEach((key, value) -> {
                if (value == null) System.clearProperty(key);
                else System.setProperty(key, value);
            });
        }

        String sessionHeader = session.getName() + "=" + session.getValue();
        var anonymous = invoke(lambda, "GET", "/subscription", "2001:db8::1", "192.0.2.1", "", "");
        assertThat(anonymous.path("statusCode").asInt()).isEqualTo(200);
        assertThat(anonymous.path("cookies").get(0).asText()).startsWith("__Host-brief-csrf=");
        assertThat(anonymous.has("multiValueHeaders")).isFalse();
        var page = invoke(lambda, "GET", "/subscription/settings", "2001:db8::1", "192.0.2.1", sessionHeader, "");
        assertThat(page.path("statusCode").asInt()).isEqualTo(200);
        var csrf = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"").matcher(page.path("body").asText());
        assertThat(csrf.find()).isTrue();
        String form = "_csrf=" + URLEncoder.encode(csrf.group(1), StandardCharsets.UTF_8);
        var saved = invoke(lambda, "POST", "/subscription/settings", "2001:db8::1", "192.0.2.1", sessionHeader, form + "&news=true");
        assertThat(saved.path("statusCode").asInt()).isEqualTo(200);
        assertThat(db.queryForObject("SELECT news_enabled FROM brief_subscriber", Boolean.class)).isTrue();

        String signup = form + "&news=true&email=unregistered%40example.com";
        assertThat(invoke(lambda, "POST", "/subscription", "invalid", "192.0.2.1", sessionHeader, signup)
                .path("statusCode").asInt()).isEqualTo(400);
        assertThat(invoke(lambda, "POST", "/subscription", "2001:db8::1", "192.0.2.1", sessionHeader, signup)
                .path("statusCode").asInt()).isEqualTo(200);
        assertThat(invoke(lambda, "POST", "/subscription", "2001:0db8:0:0:0:0:0:1", "192.0.2.2", sessionHeader, signup)
                .path("statusCode").asInt()).isEqualTo(200);
        assertThat(invoke(lambda, "POST", "/subscription", "192.0.2.10", "192.0.2.2", sessionHeader, signup)
                .path("statusCode").asInt()).isEqualTo(200);
        assertThat(invoke(lambda, "POST", "/subscription", "192.0.2.10", "192.0.2.3", sessionHeader, signup)
                .path("statusCode").asInt()).isEqualTo(200);
        assertThat(db.queryForList("SELECT count FROM brief_request_limit WHERE kind='ip' ORDER BY count", Integer.class))
                .containsExactly(1, 2, 2);
    }

    private JsonNode invoke(WebLambdaHandler lambda, String method, String path, String ip,
            String forwarded, String cookie, String body) throws Exception {
        var mapper = new ObjectMapper();
        var event = Map.of("version", "2.0", "rawPath", path, "rawQueryString", "",
                "cookies", cookie.isEmpty() ? List.of() : List.of(cookie), "isBase64Encoded", false, "body", body,
                "headers", Map.of("host", "brief.example.com", "x-forwarded-proto", "https",
                        "x-forwarded-for", forwarded, "content-type", "application/x-www-form-urlencoded"),
                "requestContext", Map.of("stage", "$default", "requestId", "test",
                        "http", Map.of("method", method, "path", path, "protocol", "HTTP/1.1", "sourceIp", ip)));
        var context = mock(Context.class);
        when(context.getAwsRequestId()).thenReturn("test");
        var output = new ByteArrayOutputStream();
        lambda.handleRequest(new ByteArrayInputStream(mapper.writeValueAsBytes(event)), output, context);
        return mapper.readTree(output.toByteArray());
    }
}
