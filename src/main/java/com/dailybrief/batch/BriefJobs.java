package com.dailybrief.batch;

import com.dailybrief.collection.CollectionService;
import com.dailybrief.delivery.DeliveryService;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.batch.support.transaction.ResourcelessTransactionManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.time.Instant;
import java.util.function.Consumer;

@Configuration(proxyBeanMethods = false)
@Profile("!web-lambda")
class BriefJobs {

    static final String CUTOFF = "cutoff";
    static final String DAILY = "dailyBrief";
    static final String RETRY = "retryBriefDelivery";

    @Bean(DAILY)
    Job dailyBrief(JobRepository jobs, CollectionService collection, DeliveryService delivery) {
        return new JobBuilder(DAILY, jobs)
                .start(step(jobs, "collectBriefSources", atCutoff(collection::collect)))
                .next(step(jobs, "summarizeBriefReleases", atCutoff(collection::summarize)))
                .next(step(jobs, "sendDailyBrief", atCutoff(delivery::prepareAndSend)))
                .build();
    }

    @Bean(RETRY)
    Job retryBriefDelivery(JobRepository jobs, DeliveryService delivery) {
        return new JobBuilder(RETRY, jobs)
                .start(
                        step(
                                jobs,
                                "retryExistingBriefs",
                                (contribution, context) -> {
                                    delivery.retry();
                                    return RepeatStatus.FINISHED;
                                }))
                .build();
    }

    private Tasklet atCutoff(Consumer<Instant> work) {
        return (contribution, context) -> {
            var cutoff = contribution.getStepExecution().getJobParameters().getString(CUTOFF);
            work.accept(Instant.parse(cutoff));
            return RepeatStatus.FINISHED;
        };
    }

    private Step step(JobRepository jobs, String name, Tasklet tasklet) {
        // The services commit their own database changes. A step must not pin a JDBC
        // transaction or connection while it waits for a provider.
        return new StepBuilder(name, jobs)
                .tasklet(tasklet, new ResourcelessTransactionManager())
                .build();
    }
}
