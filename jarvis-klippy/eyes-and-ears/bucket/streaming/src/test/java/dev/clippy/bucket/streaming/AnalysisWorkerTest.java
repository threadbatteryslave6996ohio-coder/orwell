package dev.clippy.bucket.streaming;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.List;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class AnalysisWorkerTest {
    @Test
    void extractsEveryFrameWhenOneReadContainsMultipleJpegs() {
        byte[] first = jpeg((byte) 1, (byte) 2);
        byte[] second = jpeg((byte) 3, (byte) 4);
        byte[] stream = new byte[first.length + second.length];
        System.arraycopy(first, 0, stream, 0, first.length);
        System.arraycopy(second, 0, stream, first.length, second.length);

        List<byte[]> frames = StreamSupport.stream(
                AnalysisWorker.extractFrames(new ByteArrayInputStream(stream)).spliterator(), false
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
                AnalysisWorker.extractFrames(new ByteArrayInputStream(stream)).spliterator(), false
        ).toList();

        assertEquals(1, frames.size());
        assertArrayEquals(complete, frames.get(0));
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
