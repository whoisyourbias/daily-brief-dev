package com.dailybrief.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.batch.core.repository.support.JobRepositoryFactoryBean;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.annotation.Isolation;

@EnabledIfEnvironmentVariable(named = "DSQL_BATCH_TEST_URL", matches = ".+")
class DsqlBatchMetadataTest {

    @Test
    void createsBatchMetadataAndReadsBackJobAndStepExecutions() throws Exception {
        String url = System.getenv("DSQL_BATCH_TEST_URL");
        var dataSource = new DriverManagerDataSource(url, "admin", System.getenv("DSQL_BATCH_TEST_TOKEN"));
        var jdbc = new JdbcTemplate(dataSource);

        // Every invocation starts with an empty schema in the disposable cluster.
        String schema = "batch_probe_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE SCHEMA " + schema);
        dataSource.setSchema(schema);
        applyBatchSchema(jdbc);
        verifyForeignKeys(jdbc);
        System.out.println("PASS: six Batch metadata tables, five enforced foreign keys, three CACHE 1 sequences");

        var transactionManager = new JdbcTransactionManager(dataSource);
        var factory = new JobRepositoryFactoryBean();
        factory.setDataSource(dataSource);
        factory.setTransactionManager(transactionManager);
        factory.setIsolationLevelForCreateEnum(Isolation.REPEATABLE_READ);
        factory.afterPropertiesSet();
        var repository = factory.getObject();

        var step = new StepBuilder("metadataProbeStep", repository)
                .tasklet((contribution, chunkContext) -> {
                    var execution = contribution.getStepExecution();
                    execution.getExecutionContext().putString("step-marker", "persisted-step");
                    execution.getJobExecution().getExecutionContext().putString("job-marker", "persisted-job");
                    return RepeatStatus.FINISHED;
                }, transactionManager)
                .build();
        var job = new JobBuilder("metadataProbeJob", repository).start(step).build();
        var parameters = new JobParametersBuilder().addString("probe", "dsql").toJobParameters();
        var launcher = new TaskExecutorJobLauncher();
        launcher.setJobRepository(repository);
        launcher.afterPropertiesSet();

        var launched = launcher.run(job, parameters);
        assertThat(launched.getAllFailureExceptions()).isEmpty();
        assertThat(launched.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // DriverManagerDataSource opens a new physical connection for each transaction.
        var jobExecution = repository.getLastJobExecution(job.getName(), parameters);
        assertThat(jobExecution).isNotNull();
        assertThat(jobExecution.getId()).isEqualTo(launched.getId());
        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(jobExecution.getJobParameters()).isEqualTo(parameters);
        assertThat(jobExecution.getExecutionContext().getString("job-marker")).isEqualTo("persisted-job");

        var stepExecution = repository.getLastStepExecution(jobExecution.getJobInstance(), step.getName());
        assertThat(stepExecution).isNotNull();
        assertThat(stepExecution.getJobExecutionId()).isEqualTo(jobExecution.getId());
        assertThat(stepExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(stepExecution.getCommitCount()).isEqualTo(1);
        assertThat(stepExecution.getExecutionContext().getString("step-marker")).isEqualTo("persisted-step");
        System.out.println("PASS: JobRepository round trip for job parameters, job and step status, and both contexts");
    }

    private static void applyBatchSchema(JdbcTemplate jdbc) throws Exception {
        var resource = new ClassPathResource("org/springframework/batch/core/schema-postgresql.sql");
        String sql = resource.getContentAsString(StandardCharsets.UTF_8);
        var statements = Arrays.stream(sql.replaceAll("(?m)^\\s*--[^\\r\\n]*", "").split(";"))
                .map(String::strip)
                .filter(statement -> !statement.isEmpty())
                .toList();

        assertThat(statements).hasSize(9);
        for (String statement : statements) {
            if (statement.startsWith("CREATE TABLE BATCH_")) {
                jdbc.execute(statement);
            } else {
                assertThat(statement).matches("CREATE SEQUENCE BATCH_[A-Z_]+ MAXVALUE [0-9]+ NO CYCLE");
                jdbc.execute(statement.replace(" MAXVALUE ", " CACHE 1 MAXVALUE "));
            }
        }
        assertThat(jdbc.queryForList(
                "SELECT sequencename FROM pg_sequences WHERE schemaname = current_schema() AND cache_size = 1",
                String.class))
                .containsExactlyInAnyOrder("batch_job_seq", "batch_job_execution_seq", "batch_step_execution_seq");
    }

    private static void verifyForeignKeys(JdbcTemplate jdbc) {
        for (String insert : List.of(
                "INSERT INTO batch_job_execution (job_execution_id, job_instance_id, create_time) VALUES (-1, -1, now())",
                "INSERT INTO batch_job_execution_params VALUES (-1, 'test', 'java.lang.String', 'test', 'Y')",
                "INSERT INTO batch_step_execution (step_execution_id, version, step_name, job_execution_id, "
                        + "create_time) VALUES (-1, 0, 'test', -1, now())",
                "INSERT INTO batch_job_execution_context (job_execution_id, short_context) VALUES (-1, 'test')",
                "INSERT INTO batch_step_execution_context (step_execution_id, short_context) VALUES (-1, 'test')")) {
            assertThatThrownBy(() -> jdbc.update(insert))
                    .hasRootCauseInstanceOf(SQLException.class)
                    .rootCause().extracting(error -> ((SQLException) error).getSQLState())
                    .isEqualTo("23503");
        }
    }
}
