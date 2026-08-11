package dev.orwell.bucket.detection;

import dev.orwell.bucket.detection.entity.FrameEventEntity;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static dev.orwell.bucket.detection.FrameTestFixtures.flat;
import static dev.orwell.bucket.detection.FrameTestFixtures.request;
import static dev.orwell.bucket.detection.FrameTestFixtures.withBlock;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What ingest does with a pushed frame: which ones it keeps, and in what order it broadcasts and
 * stores them. The ordering is the point — the frame goes out before it is written, which is what
 * keeps a database stall off the live path.
 */
class FrameIngestServiceTest {
    private final FrameHub hub = mock(FrameHub.class);
    private final FrameStoreWriter store = mock(FrameStoreWriter.class);
    private final FrameIdAllocator ids = mock(FrameIdAllocator.class);
    private final AtomicLong nextId = new AtomicLong(1);

    private FrameIngestService service(String mode) {
        when(ids.next()).thenAnswer(call -> nextId.getAndIncrement());
        return new FrameIngestService(new MotionService(12, 0.02), ids, store, hub, mode);
    }

    @Test
    void aFrameIsBroadcastBeforeItIsStored() {
        FrameIngestService service = service("changed");

        service.ingest(request("cam1", flat(100)));

        InOrder order = inOrder(hub, store);
        order.verify(hub).broadcast(any(FrameEventEntity.class));
        order.verify(store).submit(any(FrameEventEntity.class));
    }

    @Test
    void theBroadcastFrameAlreadyCarriesItsStoredId() {
        FrameIngestService service = service("changed");

        Map<String, Object> response = service.ingest(request("cam1", flat(100)));

        ArgumentCaptor<FrameEventEntity> captor = ArgumentCaptor.captor();
        verify(hub).broadcast(captor.capture());
        // The id exists before the row does, so the SSE event id and the row id are the same
        // value even though the write has not happened yet.
        assertThat(captor.getValue().getId()).isEqualTo(response.get("frameId"));
        assertThat(captor.getValue().getId()).isNotNull();
    }

    @Test
    void changedModeKeepsTheFirstFrameAsABaseline() {
        FrameIngestService service = service("changed");
        when(hub.broadcast(any(FrameEventEntity.class))).thenReturn(2);

        Map<String, Object> response = service.ingest(request("cam1", flat(100)));

        assertThat(response.get("stored")).isEqualTo(true);
        assertThat(response.get("firstFrame")).isEqualTo(true);
        assertThat(response.get("recipients")).isEqualTo(2);
        verify(store, times(1)).submit(any(FrameEventEntity.class));
    }

    @Test
    void changedModeDropsAFrameIdenticalToTheLastOne() {
        FrameIngestService service = service("changed");
        byte[] frame = flat(100);
        service.ingest(request("cam1", frame));

        Map<String, Object> response = service.ingest(request("cam1", frame));

        assertThat(response.get("stored")).isEqualTo(false);
        assertThat(response.get("recipients")).isEqualTo(0);
        // Only the baseline went out, and no id was burned on the frame that was dropped.
        verify(hub, times(1)).broadcast(any(FrameEventEntity.class));
        verify(store, times(1)).submit(any(FrameEventEntity.class));
        assertThat(service.framesReceivedTotal()).isEqualTo(2L);
        assertThat(service.framesStoredTotal()).isEqualTo(1L);
    }

    @Test
    void changedModeKeepsAFrameThatDiffers() {
        FrameIngestService service = service("changed");
        service.ingest(request("cam1", flat(100)));

        Map<String, Object> response = service.ingest(request("cam1", withBlock(100, 200)));

        assertThat(response.get("stored")).isEqualTo(true);
        assertThat(response.get("changed")).isEqualTo(true);
        verify(hub, times(2)).broadcast(any(FrameEventEntity.class));
    }

    @Test
    void allModeKeepsEveryFrameEvenWhenNothingMoves() {
        FrameIngestService service = service("all");
        byte[] frame = flat(100);

        service.ingest(request("cam1", frame));
        Map<String, Object> response = service.ingest(request("cam1", frame));

        assertThat(response.get("stored")).isEqualTo(true);
        assertThat(response.get("changed")).isEqualTo(false);
        verify(hub, times(2)).broadcast(any(FrameEventEntity.class));
    }

    @Test
    void theFrameCarriesTheSourceHashAndBytes() {
        FrameIngestService service = service("changed");
        Map<String, Object> payload = request("cam9", flat(100));
        payload.put("frameIndex", 42);

        service.ingest(payload);

        ArgumentCaptor<FrameEventEntity> captor = ArgumentCaptor.captor();
        verify(store).submit(captor.capture());
        FrameEventEntity frame = captor.getValue();
        assertThat(frame.getSource()).isEqualTo("cam9");
        assertThat(frame.getFrameIndex()).isEqualTo(42L);
        assertThat(frame.getSha256()).hasSize(64);
        assertThat(frame.getFrameBytes()).isNotEmpty();
    }

    @Test
    void aBadFrameIsRejectedBeforeAnythingIsBroadcastOrStored() {
        FrameIngestService service = service("changed");
        Map<String, Object> payload = request("cam1", new byte[] {1, 2, 3, 4});

        assertThrows(FramePayload.InvalidFrameException.class, () -> service.ingest(payload));

        verify(hub, never()).broadcast(any(FrameEventEntity.class));
        verify(store, never()).submit(any(FrameEventEntity.class));
    }

    @Test
    void aFrameStoredWithNobodyConnectedReportsNoRecipients() {
        FrameIngestService service = service("changed");
        when(hub.broadcast(any(FrameEventEntity.class))).thenReturn(0);

        Map<String, Object> response = service.ingest(request("cam1", flat(100)));

        assertThat(response.get("stored")).isEqualTo(true);
        assertThat(response.get("recipients")).isEqualTo(0);
        // Nobody was watching, but it is still queued for storage so a later client can replay it.
        verify(store).submit(any(FrameEventEntity.class));
    }
}
