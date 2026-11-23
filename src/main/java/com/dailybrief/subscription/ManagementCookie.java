package com.dailybrief.subscription;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.util.WebUtils;

@Component
final class ManagementCookie {
    private final boolean secure;
    private final String name;

    ManagementCookie(@Value("${server.servlet.session.cookie.secure:false}") boolean secure) {
        this.secure = secure;
        name = secure ? "__Host-brief-management" : "brief-management";
    }

    String read(HttpServletRequest request) {
        var cookie = WebUtils.getCookie(request, name);
        return cookie == null ? null : cookie.getValue();
    }

    void write(HttpServletResponse response, String token) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(name, token)
                .path("/").httpOnly(true).secure(secure).sameSite("Lax")
                .maxAge(token.isEmpty() ? 0 : -1).build().toString());
    }
}
