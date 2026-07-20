package dev.orwell.bucket.proxy.streaming;

import org.junit.jupiter.api.Test;

import dev.orwell.logging.Logger;

import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.List;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class AnalysisWorkerTest {
    private static final Logger NO_OP_LOGGER = entry -> {
    };

    @Test
    void extractsEveryFrameWhenOneReadContainsMultipleJpegs() {
        byte[] first = jpeg((byte) 1, (byte) 2);
        byte[] second = jpeg((byte) 3, (byte) 4);
        byte[] stream = new byte[first.length + second.length];
        System.arraycopy(first, 0, stream, 0, first.length);
        System.arraycopy(second, 0, stream, first.length, second.length);

        List<byte[]> frames = StreamSupport.stream(
                AnalysisWorker.extractFrames(new ByteArrayInputStream(stream), NO_OP_LOGGER).spliterator(), false
        ).toList();

        assertEquals(2, frames.size());
        assertArrayEquals(first, frames.get(0));
        assertArrayEquals(second, frames.get(1));
    }

    @Test
    void ignoresAnIncompleteTrailingFrame() {
        byte[] complete = jpeg((byte) 1);
        byte[] incomplete = {(byte) 0xFF, (byte) 0xD8, 2, 3};
        byte[] stream = Arrays.copyOf(complete, complete.length + incomplete.length);
        System.arraycopy(incomplete, 0, stream, complete.length, incomplete.length);

        List<byte[]> frames = StreamSupport.stream(
                AnalysisWorker.extractFrames(new ByteArrayInputStream(stream), NO_OP_LOGGER).spliterator(), false
        ).toList();

        assertEquals(1, frames.size());
        assertArrayEquals(complete, frames.get(0));
    }

    @Test
    void extractsFrameLargerThanTheReadBuffer() {
        byte[] payload = new byte[10_000];
        for (int i = 0; i < payload.length; i++) {
            // Avoid 0xFF so no spurious markers appear inside the payload.
            payload[i] = (byte) (i % 251);
        }
        byte[] frame = jpeg(payload);

        List<byte[]> frames = StreamSupport.stream(
                AnalysisWorker.extractFrames(new ByteArrayInputStream(frame), NO_OP_LOGGER).spliterator(), false
        ).toList();

        assertEquals(1, frames.size());
        assertArrayEquals(frame, frames.get(0));
    }

    @Test
    void extractsFrameWhenEoiMarkerStraddlesTheReadBoundary() {
        // Read buffer is 4096; size the payload so the EOI's 0xFF is the last byte of the
        // first read and 0xD9 is the first byte of the second read.
        byte[] payload = new byte[4093];
        byte[] frame = jpeg(payload);
        assertEquals(4097, frame.length);

        List<byte[]> frames = StreamSupport.stream(
                AnalysisWorker.extractFrames(new ByteArrayInputStream(frame), NO_OP_LOGGER).spliterator(), false
        ).toList();

        assertEquals(1, frames.size());
        assertArrayEquals(frame, frames.get(0));
    }

    @Test
    void recoversAfterAnOversizeFrameWithoutAnEndMarker() {
        // A long run of SOI-then-no-EOI (larger than the 32 MB cap) must not OOM, and a valid
        // frame arriving afterwards must still be extracted.
        byte[] runaway = new byte[33 * 1024 * 1024]; // just over the 32 MB cap
        runaway[0] = (byte) 0xFF;
        runaway[1] = (byte) 0xD8; // an SOI with no matching EOI in the whole run
        byte[] good = jpeg((byte) 7, (byte) 8);
        byte[] stream = Arrays.copyOf(runaway, runaway.length + good.length);
        System.arraycopy(good, 0, stream, runaway.length, good.length);

        List<byte[]> frames = StreamSupport.stream(
                AnalysisWorker.extractFrames(new ByteArrayInputStream(stream), NO_OP_LOGGER).spliterator(), false
        ).toList();

        assertEquals(1, frames.size());
        assertArrayEquals(good, frames.get(0));
    }

    private static byte[] jpeg(byte... payload) {
        byte[] frame = new byte[payload.length + 4];
        frame[0] = (byte) 0xFF;
        frame[1] = (byte) 0xD8;
        System.arraycopy(payload, 0, frame, 2, payload.length);
        frame[frame.length - 2] = (byte) 0xFF;
        frame[frame.length - 1] = (byte) 0xD9;
        return frame;
    }
}
