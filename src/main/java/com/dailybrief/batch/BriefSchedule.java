package com.dailybrief.batch;

import com.dailybrief.delivery.DeliveryService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.JobRestartException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "dailybrief.scheduling.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class BriefSchedule {

    private static final Logger log = LoggerFactory.getLogger(BriefSchedule.class);
    private static final String SEOUL = "Asia/Seoul";
    private static final LocalTime SEND_TIME = LocalTime.of(8, 0);

    private final JobLauncher launcher;
    private final JobRepository jobs;
    private final Job daily;
    private final Job retry;
    private final DeliveryService delivery;
    private final Clock clock;
    private final Instant activation;

    BriefSchedule(
            JobLauncher launcher,
            JobRepository jobs,
            @Qualifier(BriefJobs.DAILY) Job daily,
            @Qualifier(BriefJobs.RETRY) Job retry,
            DeliveryService delivery,
            Clock clock,
            @Value("${dailybrief.scheduling.activation-at}") String activation) {
        this.launcher = launcher;
        this.jobs = jobs;
        this.daily = daily;
        this.retry = retry;
        this.delivery = delivery;
        this.clock = clock;
        if (activation.isBlank()) {
            throw new IllegalArgumentException(
                    "Set dailybrief.scheduling.activation-at when scheduling is enabled.");
        }
        this.activation = Instant.parse(activation);
    }

    @EventListener(ApplicationReadyEvent.class)
    void resumeMissedCutoff() {
        launchLatestCutoff();
    }

    @Scheduled(cron = "0 0 8 * * *", zone = SEOUL)
    void scheduledDaily() {
        launchLatestCutoff();
    }

    @Scheduled(fixedDelayString = "${dailybrief.scheduling.retry-delay}")
    void scheduledRetry() {
        if (!clock.instant().isBefore(activation) && delivery.hasRetryWork()) {
            launch(
                    retry,
                    new JobParametersBuilder()
                            .addString("run", UUID.randomUUID().toString())
                            .toJobParameters());
        }
    }

    private void launchLatestCutoff() {
        Instant cutoff = latestCutoff(clock.instant());
        if (cutoff.isBefore(activation)) {
            return;
        }
        JobParameters parameters =
                new JobParametersBuilder()
                        .addString(BriefJobs.CUTOFF, cutoff.toString())
                        .toJobParameters();
        if (jobs.getLastJobExecution(daily.getName(), parameters) == null) {
            launch(daily, parameters);
        }
    }

    static Instant latestCutoff(Instant now) {
        var seoul = now.atZone(ZoneId.of(SEOUL));
        var date = seoul.toLocalDate();
        if (seoul.toLocalTime().isBefore(SEND_TIME)) {
            date = date.minusDays(1);
        }
        return date.atTime(SEND_TIME).atZone(seoul.getZone()).toInstant();
    }

    private void launch(Job job, JobParameters parameters) {
        try {
            launcher.run(job, parameters);
        } catch (JobExecutionAlreadyRunningException
                | JobInstanceAlreadyCompleteException ignored) {
            log.debug("Another trigger already started {}", job.getName());
        } catch (JobRestartException | JobParametersInvalidException failure) {
            log.error("Unable to launch {}", job.getName(), failure);
        }
    }
}
