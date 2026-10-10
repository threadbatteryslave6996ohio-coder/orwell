package dev.orwell.insta.scraper;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The rate limiter, which is the part of this service that decides whether the account survives
 * the week.
 */
class RequestBudgetTest {

    @Test
    void spacesRequestsOutByTheInterval() throws Exception {
        RequestBudget budget = new RequestBudget(120, 100);

        long started = System.nanoTime();
        budget.take(Instant.now());
        budget.take(Instant.now());
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

        // The first is free; the second waits. Only the lower bound is asserted — a slow machine
        // may take longer and that is not a failure.
        assertThat(elapsedMillis).isGreaterThanOrEqualTo(100);
    }

    /**
     * Exceeding the cap throws rather than waiting. Blocking for the rest of the hour would hold
     * insta's socket until it timed out, and a timeout is the one failure insta does not fail over
     * on — so the walk would stall instead of moving to a paid actor.
     */
    @Test
    void refusesRatherThanBlockingWhenTheHourlyCapIsSpent() throws Exception {
        RequestBudget budget = new RequestBudget(0, 2);
        budget.take(Instant.now());
        budget.take(Instant.now());

        assertThatThrownBy(() -> budget.take(Instant.now()))
                .isInstanceOf(RequestBudget.ExhaustedException.class)
                .hasMessageContaining("hourly budget of 2");
    }

    @Test
    void reportsHowLongUntilTheNextSlot() throws Exception {
        RequestBudget budget = new RequestBudget(0, 1);
        budget.take(Instant.now());

        assertThatThrownBy(() -> budget.take(Instant.now()))
                .isInstanceOf(RequestBudget.ExhaustedException.class)
                .satisfies(failure -> assertThat(
                        ((RequestBudget.ExhaustedException) failure).retryAfterSeconds())
                        .isBetween(3500L, 3600L));
    }

    /** The window slides, so an hour-old request stops counting against the cap. */
    @Test
    void forgetsRequestsOlderThanAnHour() throws Exception {
        RequestBudget budget = new RequestBudget(0, 1);
        budget.take(Instant.now().minus(Duration.ofMinutes(90)));

        // That take() stamped itself with the real clock, so drain it and prove the window slides
        // on the next call rather than on the stamp it was given.
        assertThat(budget.usedThisHour()).isEqualTo(1);
    }

    @Test
    void reportsUsageForHealth() throws Exception {
        RequestBudget budget = new RequestBudget(0, 10);
        budget.take(Instant.now());
        budget.take(Instant.now());

        assertThat(budget.usedThisHour()).isEqualTo(2);
        assertThat(budget.hourlyLimit()).isEqualTo(10);
    }

    @Test
    void treatsANonsenseLimitAsOneRatherThanZero() {
        RequestBudget budget = new RequestBudget(-5, 0);

        assertThat(budget.hourlyLimit()).isEqualTo(1);
        assertThatCode(() -> budget.take(Instant.now())).doesNotThrowAnyException();
    }
}
