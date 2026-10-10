package dev.orwell.auth.http.server;

import dev.orwell.testing.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.IntervalTask;
import org.springframework.scheduling.config.ScheduledTask;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sweep is wired by three separate things — {@code @EnableScheduling} on the application, the
 * condition on the bean, and a rate resolved from a property — and any one of them being wrong
 * leaves a server that looks healthy and never collects a token. Nothing else would notice, so
 * this asserts the timer exists and carries the configured period.
 */
@SpringBootTest(properties = "orwell.auth.token-cleanup.interval-seconds=1800")
class TokenCleanupSchedulingTest extends PostgresIntegrationTest {

    @Autowired
    private ApplicationContext context;
    @Autowired
    private ScheduledAnnotationBeanPostProcessor scheduling;

    @Test
    void registersTheSweepOnATimerAtTheConfiguredRate() {
        assertThat(context.getBeansOfType(TokenCleanupJob.class))
                .as("the job runs by default; a server nobody configured is the one that has been accumulating rows")
                .isNotEmpty();

        assertThat(scheduling.getScheduledTasks())
                .map(ScheduledTask::getTask)
                .filteredOn(IntervalTask.class::isInstance)
                .map(task -> ((IntervalTask) task).getIntervalDuration())
                .contains(Duration.ofSeconds(1800));
    }
}
