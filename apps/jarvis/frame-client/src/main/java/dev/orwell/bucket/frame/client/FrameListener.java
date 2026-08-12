package dev.orwell.bucket.frame.client;

/**
 * What a watcher does with each frame.
 *
 * <p>Called on the client's own reader thread, one frame at a time and in id order. A slow
 * listener therefore slows this client's reads, which is what makes the hub start dropping this
 * connection's oldest frames — so do the expensive part somewhere else if it cannot keep up with
 * the stream. This is the same trade the hub documents from the other side.
 *
 * <p>An exception thrown here is logged and swallowed: one undecodable frame must not end a
 * subscription that has been running for a week.
 */
@FunctionalInterface
public interface FrameListener {
    void onFrame(Frame frame) throws Exception;
}
