package com.dailybrief.subscription;

import com.dailybrief.common.BriefProject;
import com.dailybrief.common.MailKind;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@Controller
public class SubscriptionController {
    private final SubscriptionService service;

    public SubscriptionController(SubscriptionService service) {
        this.service = service;
    }

    private static final String RECEIPT = "요청을 접수했습니다. 해당 주소로 메일을 확인해 주세요.";

    private String ip(HttpServletRequest r) {
        return r.getRemoteAddr();
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
        service.send(service.signup(email, p, ip(request)));
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
            service.send(service.renew(email, "MANAGE", ip(r)));
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
            Model m) {
        if (purpose.equals("confirm")) {
            var result = service.confirm(token);
            if (result != null) {
                List<String> subjects = new ArrayList<>();
                if (result.news()) subjects.add(MailKind.NEWS.subject());
                if (result.technology()) subjects.add(MailKind.TECHNOLOGY.subject());
                m.addAttribute("subjects", subjects);
                return "subscription/confirmed";
            }
        } else {
            var access = service.openManagement(token);
            if (access != null) {
                HttpSession existing = r.getSession(false);
                if (existing != null) existing.invalidate();
                HttpSession session = r.getSession(true);
                session.setMaxInactiveInterval(900);
                session.setAttribute("subscriberAccess", access);
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
            service.send(
                    service.renew(email, purpose.equals("confirm") ? "CONFIRM" : "MANAGE", ip(r)));
            m.addAttribute("receipt", RECEIPT);
        }
        return "subscription/link";
    }

    private SubscriptionService.ManagementSession sessionAccess(HttpServletRequest r) {
        HttpSession s = r.getSession(false);
        return s == null
                ? null
                : (SubscriptionService.ManagementSession) s.getAttribute("subscriberAccess");
    }

    @GetMapping("/subscription/settings")
    public String settings(HttpServletRequest r, Model m) {
        var access = sessionAccess(r);
        if (access == null) return "redirect:/subscription/request-management";
        var p = service.preferences(access.subscriberId(), access.generation());
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
        var access = sessionAccess(r);
        if (access == null) return "redirect:/subscription/request-management";
        common(m);
        var p = parse(news, technology, projects, m, false);
        if (p == null) return "subscription/settings";
        if (!service.save(access.subscriberId(), access.generation(), p))
            return "redirect:/subscription/request-management";
        m.addAttribute("saved", true);
        return "subscription/settings";
    }

    @PostMapping("/subscription/unsubscribe")
    public String unsubscribe(HttpServletRequest r) {
        var access = sessionAccess(r);
        if (access != null) {
            service.unsubscribe(access.subscriberId(), access.generation());
            r.getSession().invalidate();
        }
        return "subscription/unsubscribed";
    }
}
