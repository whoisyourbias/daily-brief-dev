package com.dailybrief.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.dailybrief.delivery.DeliveryService;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

class BriefScheduleTest {

    private final JobLauncher launcher = mock(JobLauncher.class);
    private final JobRepository repository = mock(JobRepository.class);
    private final Job daily = mock(Job.class);
    private final Job retry = mock(Job.class);
    private final DeliveryService delivery = mock(DeliveryService.class);

    @Test
    void regularAndCatchUpTriggersUseTheSameLatestSeoulCutoff() throws Exception {
        var cutoff = Instant.parse("2026-11-18T23:00:00Z");
        assertThat(BriefSchedule.latestCutoff(cutoff.minusMillis(1)))
                .isEqualTo(cutoff.minusSeconds(86_400));
        assertThat(BriefSchedule.latestCutoff(cutoff)).isEqualTo(cutoff);

        JobParameters parameters =
                new JobParametersBuilder()
                        .addString(BriefJobs.CUTOFF, cutoff.toString())
                        .toJobParameters();
        when(daily.getName()).thenReturn(BriefJobs.DAILY);
        when(repository.getLastJobExecution(BriefJobs.DAILY, parameters))
                .thenReturn(null, new JobExecution(1L));

        var schedule = schedule("2026-11-19T00:00:00Z", "2026-11-17T00:00:00Z");
        schedule.resumeMissedCutoff();
        schedule.scheduledDaily();

        verify(launcher).run(daily, parameters);
    }

    @Test
    void neverStartsACutoffFromBeforeActivation() {
        var schedule = schedule("2026-11-19T00:00:00Z", "2026-11-18T23:30:00Z");
        schedule.resumeMissedCutoff();
        verifyNoInteractions(repository, launcher);
    }

    @Test
    void noRetryJobIsCreatedForAnEmptyQueue() {
        schedule("2026-11-19T00:00:00Z", "2026-11-17T00:00:00Z").scheduledRetry();
        verify(delivery).hasRetryWork();
        verifyNoInteractions(launcher);
    }

    @Test
    void enabledScheduleRequiresAnExplicitActivation() {
        assertThatThrownBy(() -> schedule("2026-11-19T00:00:00Z", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("activation-at");
    }

    private BriefSchedule schedule(String now, String activation) {
        return new BriefSchedule(
                launcher,
                repository,
                daily,
                retry,
                delivery,
                Clock.fixed(Instant.parse(now), ZoneOffset.UTC),
                activation);
    }
}
