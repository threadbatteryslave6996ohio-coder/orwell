package dev.orwell.bucket.hub;

import dev.orwell.bucket.hub.entity.FrameEventEntity;
import dev.orwell.bucket.hub.repository.FrameEventRepository;
import dev.orwell.primitives.Sha256;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static dev.orwell.bucket.frame.FrameFixtures.flat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The range query's own rules: how a window is bounded, how a page ends, and what it refuses.
 *
 * <p>The repository is mocked here because none of that is a database question — the paging
 * contract is what a caller loops on, and it has to hold whatever rows come back.
 */
class FrameQueryServiceTest {
    private static final Instant TEN = Instant.parse("2026-08-12T10:00:00Z");
    private static final Instant ELEVEN = Instant.parse("2026-08-12T11:00:00Z");

    private final FrameEventRepository events = mock(FrameEventRepository.class);
    private final FrameQueryService service = new FrameQueryService(events);

    // --- the window -----------------------------------------------------------------------------

    @Test
    void theUpperBoundIsExclusiveSoAdjacentWindowsDoNotOverlap() {
        when(events.findCapturedBetween(any(), any(), anyLong(), any())).thenReturn(List.of());

        service.range(null, TEN, ELEVEN, null, null);

        ArgumentCaptor<Instant> from = ArgumentCaptor.captor();
        ArgumentCaptor<Instant> to = ArgumentCaptor.captor();
        verify(events).findCapturedBetween(from.capture(), to.capture(), anyLong(), any());
        // >= from and < to. A frame captured exactly at 11:00 belongs to the next window, not this
        // one, so a caller walking hour by hour sees it exactly once.
        assertThat(from.getValue()).isEqualTo(TEN);
        assertThat(to.getValue()).isEqualTo(ELEVEN);
    }

    @Test
    void openEndedBoundsSpanEverythingWithoutOverflowingATimestamp() {
        when(events.findCapturedBetween(any(), any(), anyLong(), any())).thenReturn(List.of());

        service.range(null, null, null, null, null);

        ArgumentCaptor<Instant> from = ArgumentCaptor.captor();
        ArgumentCaptor<Instant> to = ArgumentCaptor.captor();
        verify(events).findCapturedBetween(from.capture(), to.capture(), anyLong(), any());
        // Instant.MIN/MAX would be year ±1000000000, which Postgres cannot store and so cannot
        // compare against — the query would fail rather than match every row.
        assertThat(from.getValue()).isEqualTo(Instant.EPOCH);
        assertThat(to.getValue()).isAfter(Instant.parse("9000-01-01T00:00:00Z"));
        // Comfortably inside what a Postgres timestamp holds (its ceiling is year 294276).
        assertThat(to.getValue()).isBefore(Instant.MAX);
    }

    @Test
    void aBackwardsWindowIsRejectedRatherThanReturningNothing() {
        assertThatThrownBy(() -> service.range(null, ELEVEN, TEN, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("from must not be after to");

        // An empty page would read as "no frames in that window", which is a different fact.
        verify(events, never()).findCapturedBetween(any(), any(), anyLong(), any());
    }

    @Test
    void anUnscopedQuerySpansEveryCameraAndAScopedOneDoesNot() {
        when(events.findCapturedBetween(any(), any(), anyLong(), any())).thenReturn(List.of());
        when(events.findCapturedBetweenBySource(any(), any(), anyLong(), anyString(), any()))
                .thenReturn(List.of());

        service.range(null, TEN, ELEVEN, null, null);
        service.range("cam-door", TEN, ELEVEN, null, null);

        verify(events).findCapturedBetween(any(), any(), anyLong(), any());
        verify(events).findCapturedBetweenBySource(any(), any(), anyLong(), eq("cam-door"), any());
    }

    // --- paging ---------------------------------------------------------------------------------

    @Test
    void aFullPageReportsWhereTheNextOneStarts() {
        when(events.findCapturedBetween(any(), any(), anyLong(), any())).thenReturn(frames(1, 4));

        Map<String, Object> response = service.range(null, TEN, ELEVEN, null, 3);

        // Four rows came back for a page of three: the fourth is the probe, not a result.
        assertThat(response.get("returned")).isEqualTo(3);
        assertThat(response.get("hasMore")).isEqualTo(true);
        // The last id *delivered*, so the next page starts after it and nothing is skipped.
        assertThat(response.get("nextAfter")).isEqualTo(3L);
        assertThat(frameIds(response)).containsExactly(1L, 2L, 3L);
    }

    @Test
    void theLastPageSaysSoRatherThanLeavingACallerLooping() {
        when(events.findCapturedBetween(any(), any(), anyLong(), any())).thenReturn(frames(1, 2));

        Map<String, Object> response = service.range(null, TEN, ELEVEN, null, 3);

        assertThat(response.get("returned")).isEqualTo(2);
        assertThat(response.get("hasMore")).isEqualTo(false);
        assertThat(response.get("nextAfter")).isNull();
    }

    @Test
    void anEmptyWindowIsAnEmptyPageNotAnError() {
        when(events.findCapturedBetween(any(), any(), anyLong(), any())).thenReturn(List.of());

        Map<String, Object> response = service.range(null, TEN, ELEVEN, null, null);

        assertThat(response.get("success")).isEqualTo(true);
        assertThat(response.get("returned")).isEqualTo(0);
        assertThat(response.get("hasMore")).isEqualTo(false);
        assertThat(response.get("frames")).isEqualTo(List.of());
    }

    @Test
    void onePageIsReadPerRequestPlusOneRowToTellWhetherThereIsAnother() {
        when(events.findCapturedBetween(any(), any(), anyLong(), any())).thenReturn(List.of());

        service.range(null, TEN, ELEVEN, null, 50);

        ArgumentCaptor<Pageable> page = ArgumentCaptor.captor();
        verify(events).findCapturedBetween(any(), any(), anyLong(), page.capture());
        // One extra row rather than a second COUNT query over a table holding whole JPEGs.
        assertThat(page.getValue().getPageSize()).isEqualTo(51);
        assertThat(page.getValue().getPageNumber()).isZero();
    }

    @Test
    void theCursorIsExclusiveSoAPageDoesNotRepeatTheLastFrameOfTheOneBefore() {
        when(events.findCapturedBetween(any(), any(), anyLong(), any())).thenReturn(List.of());

        service.range(null, TEN, ELEVEN, 3L, null);

        verify(events).findCapturedBetween(any(), any(), eq(3L), any());
    }

    @Test
    void theDefaultPageSizeAppliesWhenNoLimitIsGiven() {
        when(events.findCapturedBetween(any(), any(), anyLong(), any())).thenReturn(List.of());

        Map<String, Object> response = service.range(null, TEN, ELEVEN, null, null);

        assertThat(response.get("limit")).isEqualTo(FrameQueryService.DEFAULT_LIMIT);
    }

    // --- refusals -------------------------------------------------------------------------------

    @Test
    void aLimitOverTheCeilingIsRefusedRatherThanQuietlyClamped() {
        assertThatThrownBy(() -> service.range(null, TEN, ELEVEN, null, FrameQueryService.MAX_LIMIT + 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit must be between 1 and " + FrameQueryService.MAX_LIMIT);

        // Clamping would hand back a short page, which a caller reads as the end of the window.
        verify(events, never()).findCapturedBetween(any(), any(), anyLong(), any());
    }

    @Test
    void aLimitOfZeroOrLessIsRefused() {
        assertThatThrownBy(() -> service.range(null, TEN, ELEVEN, null, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.range(null, TEN, ELEVEN, null, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- payload --------------------------------------------------------------------------------

    @Test
    void aQueriedFrameLooksExactlyLikeAStreamedOne() {
        when(events.findCapturedBetween(any(), any(), anyLong(), any())).thenReturn(frames(7, 1));

        Map<String, Object> response = service.range(null, TEN, ELEVEN, null, null);

        // Same builder as the live and replay paths, so jarvis-frame-client can decode either.
        @SuppressWarnings("unchecked")
        Map<String, Object> frame = ((List<Map<String, Object>>) response.get("frames")).get(0);
        assertThat(frame).containsOnlyKeys(
                "frameId", "source", "frameIndex", "capturedAt", "sha256", "frameBase64");
        assertThat(frame.get("frameId")).isEqualTo(7L);
        assertThat((String) frame.get("frameBase64")).isNotEmpty();
    }

    @Test
    void theResponseEchoesTheWindowItAnswered() {
        when(events.findCapturedBetweenBySource(any(), any(), anyLong(), anyString(), any()))
                .thenReturn(List.of());

        Map<String, Object> response = service.range("cam-door", TEN, ELEVEN, 12L, 25);

        assertThat(response.get("source")).isEqualTo("cam-door");
        assertThat(response.get("from")).isEqualTo(TEN.toString());
        assertThat(response.get("to")).isEqualTo(ELEVEN.toString());
        assertThat(response.get("after")).isEqualTo(12L);
        assertThat(response.get("limit")).isEqualTo(25);
    }

    // --- helpers --------------------------------------------------------------------------------

    /** {@code count} frames with consecutive ids from {@code firstId}, one second apart. */
    private static List<FrameEventEntity> frames(long firstId, int count) {
        List<FrameEventEntity> frames = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            byte[] bytes = flat(100 + index);
            frames.add(new FrameEventEntity(
                    firstId + index,
                    "cam-door",
                    (long) index,
                    Sha256.hex(bytes),
                    TEN.plusSeconds(index),
                    bytes));
        }
        return frames;
    }

    private static List<Long> frameIds(Map<String, Object> response) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> frames = (List<Map<String, Object>>) response.get("frames");
        return frames.stream().map(frame -> (Long) frame.get("frameId")).toList();
    }
}
