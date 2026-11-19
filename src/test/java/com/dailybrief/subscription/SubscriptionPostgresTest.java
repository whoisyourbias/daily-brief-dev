package com.dailybrief.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.dailybrief.common.MailGateway;
import com.dailybrief.common.MailKind;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
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

    @BeforeEach
    void clean() {
        db.execute(
                "TRUNCATE brief_subscription_token, brief_project_selection, brief_subscriber,"
                    + " brief_request_limit CASCADE");
        reset(gateway);
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
        mvc.perform(post("/subscription").with(csrf()).param("email", "a@example.com"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("메일을 하나 이상 선택")));
        mvc.perform(
                        post("/subscription")
                                .with(csrf())
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
                        post("/subscription")
                                .with(csrf())
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
        db.update("UPDATE brief_subscription_token SET expires_at=now()-interval '1 second'");
        db.update(
                "UPDATE brief_request_limit SET window_start=now()-interval '16 minutes' WHERE"
                    + " kind='address'");
        mvc.perform(get("/subscription/confirm/" + old))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("링크 다시 요청")));
        assertThat(db.queryForObject("SELECT status FROM brief_subscriber", String.class))
                .isEqualTo("PENDING");
        mvc.perform(
                        post("/subscription/confirm/" + old + "/renew")
                                .with(csrf())
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
        mvc.perform(post("/subscription/confirm/" + fresh).with(csrf()))
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
        mvc.perform(post("/subscription/confirm/" + fresh).with(csrf()))
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
                        post("/subscription/request-management")
                                .with(csrf())
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
                mvc.perform(post("/subscription/manage/" + token).with(csrf()))
                        .andExpect(status().is3xxRedirection())
                        .andReturn();
        var session =
                (org.springframework.mock.web.MockHttpSession)
                        result.getRequest().getSession(false);
        db.update("UPDATE brief_subscriber SET generation=3, status='ACTIVE', news_enabled=true");
        mvc.perform(post("/subscription/settings").session(session).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(db.queryForObject("SELECT news_enabled FROM brief_subscriber", Boolean.class))
                .isTrue();
        mvc.perform(post("/subscription/unsubscribe").session(session).with(csrf()))
                .andExpect(status().isOk());
        assertThat(db.queryForObject("SELECT status FROM brief_subscriber", String.class))
                .isEqualTo("ACTIVE");
    }
}
