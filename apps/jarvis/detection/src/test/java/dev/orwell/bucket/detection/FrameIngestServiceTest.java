package dev.orwell.bucket.detection;

import dev.orwell.bucket.detection.entity.FrameEventEntity;
import dev.orwell.bucket.detection.repository.FrameEventRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static dev.orwell.bucket.detection.FrameTestFixtures.flat;
import static dev.orwell.bucket.detection.FrameTestFixtures.withBlock;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What the bastion keeps. {@code DETECTION_FANOUT_MODE} is the difference between a table that
 * grows at the full ingest rate and one that only records frames worth relaying, so both modes are
 * covered here.
 */
class FrameIngestServiceTest {
    private final FrameEventRepository events = mock(FrameEventRepository.class);
    private final AtomicLong nextId = new AtomicLong(1);

    private FrameIngestService service(String mode) {
        when(events.save(any(FrameEventEntity.class))).thenAnswer(call -> {
            FrameEventEntity saved = call.getArgument(0);
            setId(saved, nextId.getAndIncrement());
            return saved;
        });
        return new FrameIngestService(new MotionService(12, 0.02), events, mode);
    }

    @Test
    void changedModeKeepsTheFirstFrameAsABaseline() {
        FrameIngestService service = service("changed");

        Map<String, Object> response = service.ingest(request("cam1", flat(100)));

        assertThat(response.get("stored")).isEqualTo(true);
        assertThat(response.get("firstFrame")).isEqualTo(true);
        assertThat(response.get("frameId")).isEqualTo(1L);
        verify(events, times(1)).save(any(FrameEventEntity.class));
    }

    @Test
    void changedModeDropsAFrameIdenticalToTheLastOne() {
        FrameIngestService service = service("changed");
        byte[] frame = flat(100);
        service.ingest(request("cam1", frame));

        Map<String, Object> response = service.ingest(request("cam1", frame));

        assertThat(response.get("stored")).isEqualTo(false);
        assertThat(response.get("frameId")).isNull();
        // Only the baseline was written.
        verify(events, times(1)).save(any(FrameEventEntity.class));
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
        verify(events, times(2)).save(any(FrameEventEntity.class));
    }

    @Test
    void allModeKeepsEveryFrameEvenWhenNothingMoves() {
        FrameIngestService service = service("all");
        byte[] frame = flat(100);

        service.ingest(request("cam1", frame));
        Map<String, Object> response = service.ingest(request("cam1", frame));

        assertThat(response.get("stored")).isEqualTo(true);
        assertThat(response.get("changed")).isEqualTo(false);
        verify(events, times(2)).save(any(FrameEventEntity.class));
    }

    @Test
    void theStoredRowCarriesTheSourceHashAndBytes() {
        FrameIngestService service = service("changed");
        byte[] frame = flat(100);
        Map<String, Object> payload = request("cam9", frame);
        payload.put("frameIndex", 42);

        service.ingest(payload);

        ArgumentCaptor<FrameEventEntity> captor = ArgumentCaptor.forClass(FrameEventEntity.class);
        verify(events).save(captor.capture());
        FrameEventEntity stored = captor.getValue();
        assertThat(stored.getSource()).isEqualTo("cam9");
        assertThat(stored.getFrameIndex()).isEqualTo(42L);
        assertThat(stored.getSha256()).hasSize(64);
        assertThat(stored.getFrameBytes()).isEqualTo(frame);
    }

    @Test
    void aNonNumericFrameIndexIsSimplyNotRecorded() {
        FrameIngestService service = service("changed");
        Map<String, Object> payload = request("cam1", flat(100));
        payload.put("frameIndex", "not-a-number");

        service.ingest(payload);

        ArgumentCaptor<FrameEventEntity> captor = ArgumentCaptor.forClass(FrameEventEntity.class);
        verify(events).save(captor.capture());
        assertThat(captor.getValue().getFrameIndex()).isNull();
    }

    @Test
    void aBadFrameIsRejectedBeforeAnythingIsWritten() {
        FrameIngestService service = service("changed");
        Map<String, Object> payload = request("cam1", new byte[] {1, 2, 3, 4});

        assertThrows(FramePayload.InvalidFrameException.class, () -> service.ingest(payload));

        verify(events, never()).save(any(FrameEventEntity.class));
    }

    private static Map<String, Object> request(String source, byte[] frame) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", source);
        payload.put("frameBase64", Base64.getEncoder().encodeToString(frame));
        return payload;
    }

    private static void setId(FrameEventEntity entity, long id) {
        try {
            Field field = FrameEventEntity.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
