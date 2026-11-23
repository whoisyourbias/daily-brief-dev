package com.dailybrief.subscription;

import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.transaction.TransactionException;

@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SubscriptionSecurityConfiguration {
    @Bean
    SecurityFilterChain subscriptionSecurity(HttpSecurity http, SubscriptionService service,
            ManagementCookie managementCookie,
            @Value("${server.servlet.session.cookie.secure:false}") boolean secure) throws Exception {
        var anonymous = new CookieCsrfTokenRepository();
        anonymous.setCookieName(secure ? "__Host-brief-csrf" : "brief-csrf");
        anonymous.setCookieCustomizer(cookie -> cookie.path("/").sameSite("Lax").secure(secure));
        CsrfTokenRepository csrfTokens = new CsrfTokenRepository() {
            @Override
            public CsrfToken generateToken(HttpServletRequest request) {
                return anonymous.generateToken(request);
            }

            @Override
            public void saveToken(CsrfToken token, HttpServletRequest request, HttpServletResponse response) {
                anonymous.saveToken(token, request, response);
            }

            @Override
            public CsrfToken loadToken(HttpServletRequest request) {
                String csrfToken = service.csrfToken(managementCookie.read(request));
                return csrfToken == null ? anonymous.loadToken(request)
                        : new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", csrfToken);
            }
        };
        Filter databaseErrors = (request, response, chain) -> {
            try {
                chain.doFilter(request, response);
            } catch (DataAccessException | TransactionException unavailable) {
                var httpResponse = (HttpServletResponse) response;
                httpResponse.setHeader("Retry-After", "1");
                httpResponse.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            }
        };
        return http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.csrfTokenRepository(csrfTokens))
                .addFilterBefore(databaseErrors, CsrfFilter.class)
                .headers(
                        headers ->
                                headers.referrerPolicy(
                                        policy -> policy.policy(ReferrerPolicy.NO_REFERRER)))
                .build();
    }
}
