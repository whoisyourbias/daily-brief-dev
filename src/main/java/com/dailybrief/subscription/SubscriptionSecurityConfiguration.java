package com.dailybrief.subscription;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;

@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SubscriptionSecurityConfiguration {
    @Bean
    SecurityFilterChain subscriptionSecurity(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .headers(
                        headers ->
                                headers.referrerPolicy(
                                        policy -> policy.policy(ReferrerPolicy.NO_REFERRER)))
                .build();
    }
}
