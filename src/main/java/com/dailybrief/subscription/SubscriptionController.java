package com.dailybrief.subscription;

import com.dailybrief.common.BriefProject;
import com.dailybrief.common.MailKind;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.TransactionException;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.sql.SQLException;
import java.util.*;
import java.util.function.Supplier;

@Controller
public class SubscriptionController {
    private static final Logger LOG = LoggerFactory.getLogger(SubscriptionController.class);
    private final SubscriptionService service;
    private final ManagementCookie managementCookie;

    public SubscriptionController(SubscriptionService service, ManagementCookie managementCookie) {
        this.service = service;
        this.managementCookie = managementCookie;
    }

    private static final String RECEIPT = "요청을 접수했습니다. 해당 주소로 메일을 확인해 주세요.";

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    ResponseEntity<Void> databaseUnavailable() {
        LOG.warn("Subscription database operation failed");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "1").build();
    }

    private static <T> T transaction(Supplier<T> operation) {
        for (int attempt = 1; ; attempt++) {
            try {
                return operation.get();
            } catch (RuntimeException error) {
                if (attempt == 3 || !serializationFailure(error)) throw error;
            }
        }
    }

    private static boolean serializationFailure(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && "40001".equals(sql.getSQLState())) return true;
        }
        return false;
    }

    private void common(Model m) {
        m.addAttribute("projects", BriefProject.values());
    }

    private SubscriptionService.Preferences parse(
            boolean news, boolean technology, List<String> projects, Model m, boolean signup) {
        if (projects == null) projects = List.of();
        Set<BriefProject> selected = EnumSet.noneOf(BriefProject.class);
        boolean invalid = false;
        for (String p : projects) {
            try {
                selected.add(BriefProject.valueOf(p));
            } catch (IllegalArgumentException e) {
                invalid = true;
            }
        }
        m.addAttribute("news", news);
        m.addAttribute("technology", technology);
        m.addAttribute("selected", projects);
        if (signup && !news && !technology) {
            m.addAttribute("mailError", "메일을 하나 이상 선택해 주세요.");
            invalid = true;
        }
        if (technology && selected.isEmpty()) {
            m.addAttribute("projectError", "프로젝트를 하나 이상 선택해 주세요.");
            invalid = true;
        }
        if (invalid && !m.containsAttribute("projectError") && !(signup && !news && !technology))
            m.addAttribute("projectError", "프로젝트 선택을 확인해 주세요.");
        return invalid ? null : new SubscriptionService.Preferences(news, technology, selected);
    }

    @GetMapping({"/", "/subscription"})
    public String signupPage(Model m) {
        common(m);
        m.addAttribute("selected", List.of());
        return "subscription/signup";
    }

    @PostMapping("/subscription")
    public String signup(
            @RequestParam(defaultValue = "") String email,
            @RequestParam(defaultValue = "false") boolean news,
            @RequestParam(defaultValue = "false") boolean technology,
            @RequestParam(required = false) List<String> projects,
            HttpServletRequest request,
            Model m) {
        common(m);
        m.addAttribute("email", email);
        var p = parse(news, technology, projects, m, true);
        if (!SubscriptionService.validEmail(email))
            m.addAttribute("emailError", "올바른 이메일 주소를 입력해 주세요.");
        if (p == null || m.containsAttribute("emailError")) return "subscription/signup";
        String ip = RequestIp.read(request);
        service.send(transaction(() -> service.signup(email, p, ip)));
        m.addAttribute("receipt", RECEIPT);
        return "subscription/signup";
    }

    @GetMapping("/subscription/request-management")
    public String managementRequest(Model m) {
        m.addAttribute("purpose", "manage");
        return "subscription/request";
    }

    @PostMapping("/subscription/request-management")
    public String managementRequestPost(
            @RequestParam(defaultValue = "") String email, HttpServletRequest r, Model m) {
        m.addAttribute("purpose", "manage");
        m.addAttribute("email", email);
        if (!SubscriptionService.validEmail(email))
            m.addAttribute("emailError", "올바른 이메일 주소를 입력해 주세요.");
        else {
            String ip = RequestIp.read(r);
            service.send(transaction(() -> service.renew(email, "MANAGE", ip)));
            m.addAttribute("receipt", RECEIPT);
        }
        return "subscription/request";
    }

    @GetMapping("/subscription/{purpose:confirm|manage}/{token}")
    public String link(@PathVariable String purpose, @PathVariable String token, Model m) {
        m.addAttribute("purpose", purpose);
        m.addAttribute("token", token);
        m.addAttribute(
                "valid",
                service.validLink(token, purpose.equals("confirm") ? "CONFIRM" : "MANAGE"));
        return "subscription/link";
    }

    @PostMapping("/subscription/{purpose:confirm|manage}/{token}")
    public String consume(
            @PathVariable String purpose,
            @PathVariable String token,
            HttpServletRequest r,
            HttpServletResponse response,
            Model m) {
        if (purpose.equals("confirm")) {
            var result = transaction(() -> service.confirm(token));
            if (result != null) {
                List<String> subjects = new ArrayList<>();
                if (result.news()) subjects.add(MailKind.NEWS.subject());
                if (result.technology()) subjects.add(MailKind.TECHNOLOGY.subject());
                m.addAttribute("subjects", subjects);
                return "subscription/confirmed";
            }
        } else {
            String previous = managementCookie.read(r);
            String sessionToken = transaction(() -> service.openManagement(token, previous));
            if (sessionToken != null) {
                managementCookie.write(response, sessionToken);
                try {
                    service.cleanExpiredSessions();
                } catch (DataAccessException error) {
                    LOG.warn("Expired management session cleanup failed");
                }
                return "redirect:/subscription/settings";
            }
        }
        m.addAttribute("purpose", purpose);
        m.addAttribute("token", token);
        m.addAttribute("valid", false);
        return "subscription/link";
    }

    @PostMapping("/subscription/{purpose:confirm|manage}/{token}/renew")
    public String renew(
            @PathVariable String purpose,
            @PathVariable String token,
            @RequestParam(defaultValue = "") String email,
            HttpServletRequest r,
            Model m) {
        m.addAttribute("purpose", purpose);
        m.addAttribute("token", token);
        m.addAttribute("valid", false);
        m.addAttribute("email", email);
        if (!SubscriptionService.validEmail(email))
            m.addAttribute("emailError", "올바른 이메일 주소를 입력해 주세요.");
        else {
            String ip = RequestIp.read(r);
            service.send(transaction(() -> service.renew(
                    email, purpose.equals("confirm") ? "CONFIRM" : "MANAGE", ip)));
            m.addAttribute("receipt", RECEIPT);
        }
        return "subscription/link";
    }

    @GetMapping("/subscription/settings")
    public String settings(HttpServletRequest r, Model m) {
        String sessionToken = managementCookie.read(r);
        var p = transaction(() -> service.preferences(sessionToken));
        if (p == null) return "redirect:/subscription/request-management";
        common(m);
        m.addAttribute("news", p.news());
        m.addAttribute("technology", p.technology());
        m.addAttribute("selected", p.projects().stream().map(Enum::name).toList());
        return "subscription/settings";
    }

    @PostMapping("/subscription/settings")
    public String save(
            @RequestParam(defaultValue = "false") boolean news,
            @RequestParam(defaultValue = "false") boolean technology,
            @RequestParam(required = false) List<String> projects,
            HttpServletRequest r,
            Model m) {
        String sessionToken = managementCookie.read(r);
        if (sessionToken == null) return "redirect:/subscription/request-management";
        common(m);
        var p = parse(news, technology, projects, m, false);
        if (p == null) {
            return transaction(() -> service.preferences(sessionToken)) == null
                    ? "redirect:/subscription/request-management" : "subscription/settings";
        }
        if (!transaction(() -> service.save(sessionToken, p)))
            return "redirect:/subscription/request-management";
        m.addAttribute("saved", true);
        return "subscription/settings";
    }

    @PostMapping("/subscription/unsubscribe")
    public String unsubscribe(HttpServletRequest r, HttpServletResponse response) {
        String sessionToken = managementCookie.read(r);
        transaction(() -> {
            service.unsubscribe(sessionToken);
            return null;
        });
        managementCookie.write(response, "");
        return "subscription/unsubscribed";
    }
}
