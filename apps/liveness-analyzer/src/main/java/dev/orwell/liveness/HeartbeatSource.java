package dev.orwell.liveness;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;

/** Supplies the most recent heartbeat instant per client id within a lookback window. */
interface HeartbeatSource {
    Map<String, Instant> latestHeartbeats(int lookbackSeconds) throws IOException, InterruptedException;
}
