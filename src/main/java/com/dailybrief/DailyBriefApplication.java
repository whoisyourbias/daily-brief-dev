package com.dailybrief;

import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableScheduling
public class DailyBriefApplication {

    public static void main(String[] args) {
        var application = new SpringApplication(DailyBriefApplication.class);
        boolean reconciliation =
                new DefaultApplicationArguments(args).containsOption("dailybrief.reconcile.id");
        if (reconciliation) {
            application.setWebApplicationType(WebApplicationType.NONE);
        }
        var context = application.run(args);
        if (reconciliation) {
            context.close();
        }
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
