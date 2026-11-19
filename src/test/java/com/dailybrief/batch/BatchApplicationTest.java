package com.dailybrief.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;

import com.dailybrief.collection.CollectionService;
import com.dailybrief.delivery.DeliveryService;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;

import javax.sql.DataSource;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class BatchApplicationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", postgres::getJdbcUrl);
        properties.add("spring.datasource.username", postgres::getUsername);
        properties.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired JobLauncher launcher;

    @Autowired
    @Qualifier(BriefJobs.DAILY)
    Job daily;

    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource datasource;
    @MockitoBean CollectionService collection;
    @MockitoBean DeliveryService delivery;

    @Test
    void migrationsAndDailyJobUseRealBatchMetadataWithoutHoldingJdbcAcrossProviderSteps()
            throws Exception {
        var cutoff = Instant.parse("2026-11-18T23:00:00Z");
        doAnswer(
                        call -> {
                            assertThat(TransactionSynchronizationManager.hasResource(datasource))
                                    .isFalse();
                            return null;
                        })
                .when(collection)
                .collect(any(Instant.class));

        var parameters =
                new JobParametersBuilder()
                        .addString(BriefJobs.CUTOFF, cutoff.toString())
                        .toJobParameters();
        var execution = launcher.run(daily, parameters);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        var order = inOrder(collection, delivery);
        order.verify(collection).collect(cutoff);
        order.verify(collection).summarize(cutoff);
        order.verify(delivery).prepareAndSend(cutoff);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM batch_job_execution WHERE job_execution_id = ?",
                                String.class,
                                execution.getId()))
                .isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM brief_subscriber", Integer.class))
                .isZero();
    }
}
