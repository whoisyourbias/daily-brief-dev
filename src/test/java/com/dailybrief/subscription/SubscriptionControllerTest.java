package com.dailybrief.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.dailybrief.common.MailKind;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.ui.ExtendedModelMap;

import java.util.List;

class SubscriptionControllerTest {
    SubscriptionService service = mock(SubscriptionService.class);
    SubscriptionController controller = new SubscriptionController(service, new ManagementCookie(false));
    MockHttpServletRequest request = new MockHttpServletRequest();

    @Test
    void signupRejectsMissingMailAndPreservesInput() {
        var model = new ExtendedModelMap();
        assertThat(
                        controller.signup(
                                "reader@example.com",
                                false,
                                false,
                                List.of("REACT"),
                                request,
                                model))
                .isEqualTo("subscription/signup");
        assertThat(model.get("mailError")).isNotNull();
        assertThat(model.get("selected")).isEqualTo(List.of("REACT"));
        verifyNoInteractions(service);
    }

    @Test
    void technologyRequiresProjectForBothForms() {
        var model = new ExtendedModelMap();
        controller.signup("reader@example.com", true, true, List.of(), request, model);
        assertThat(model.get("projectError")).isNotNull();
        request.setCookies(new Cookie("brief-management", "session"));
        when(service.preferences("session"))
                .thenReturn(new SubscriptionService.Preferences(true, false, java.util.Set.of()));
        var manage = new ExtendedModelMap();
        controller.save(false, true, List.of(), request, manage);
        assertThat(manage.get("projectError")).isNotNull();
        verify(service).preferences("session");
        verifyNoMoreInteractions(service);
    }

    @Test
    void manageAllowsBothMailsDisabled() {
        request.setCookies(new Cookie("brief-management", "session"));
        when(service.save(eq("session"), any())).thenReturn(true);
        var model = new ExtendedModelMap();
        controller.save(false, false, List.of(), request, model);
        assertThat(model.get("saved")).isEqualTo(true);
        verify(service).save(eq("session"), any());
    }

    @Test
    void expiredRenewalStaysOnPageAndValidatesAddress() {
        var model = new ExtendedModelMap();
        assertThat(controller.renew("confirm", "old", "bad", request, model))
                .isEqualTo("subscription/link");
        assertThat(model.get("emailError")).isNotNull();
        assertThat(model.get("valid")).isEqualTo(false);
        verifyNoInteractions(service);
    }

    @Test
    void confirmationShowsOnlyCommittedChoices() {
        when(service.confirm("valid"))
                .thenReturn(new SubscriptionService.Confirmation(true, false));
        var model = new ExtendedModelMap();
        var response = new MockHttpServletResponse();
        assertThat(controller.consume("confirm", "valid", request, response, model))
                .isEqualTo("subscription/confirmed");
        assertThat(model.get("subjects")).isEqualTo(List.of(MailKind.NEWS.subject()));
        var failed = new ExtendedModelMap();
        assertThat(controller.consume("confirm", "old", request, response, failed))
                .isEqualTo("subscription/link");
        assertThat(failed.containsAttribute("subjects")).isFalse();
    }
}
