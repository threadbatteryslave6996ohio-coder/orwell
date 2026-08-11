package dev.orwell.bucket.detection;

import dev.orwell.testing.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ids come from a real sequence in blocks, and the interesting cases are all at the edges: the
 * first id a process ever hands out, and what happens when a block runs out or a second process
 * shares the database.
 */
@SpringBootTest
class FrameIdAllocatorTest extends PostgresIntegrationTest {

    @DynamicPropertySource
    static void detectionProperties(DynamicPropertyRegistry registry) {
        registry.add("detection.alert-url", () -> "http://127.0.0.1:1/alerts");
        registry.add("detection.cooldown-seconds", () -> 60);
        registry.add("detection.min-confidence", () -> 0.35);
        registry.add("detection.motion.cell-threshold", () -> 12);
        registry.add("detection.motion.min-changed-fraction", () -> 0.02);
        registry.add("detection.stream.queue-depth", () -> 8);
        registry.add("detection.store.mode", () -> "async");
        registry.add("detection.store.queue-depth", () -> 512);
        registry.add("detection.frame-retention-seconds", () -> 60);
        registry.add("detection.frame-max-bytes", () -> 1024L * 1024 * 1024);
        registry.add("detection.retention-sweep-seconds", () -> 3600);
    }

    @Autowired
    private FrameIdAllocator ids;
    @Autowired
    private JdbcTemplate jdbc;

    /**
     * Id 0 is not a usable frame id. Every catch-up query means "frames after this id", and
     * {@code ?from=0} is how a client asks to replay everything still stored — so a frame handed
     * id 0 can never be replayed by anyone.
     */
    @Test
    void theFirstIdIsNeverZero() {
        assertThat(ids.next()).isPositive();
    }

    @Test
    void idsAreHandedOutInOrder() {
        List<Long> allocated = new ArrayList<>();
        // Comfortably more than one block, so this crosses at least two refills.
        for (int index = 0; index < 200; index++) {
            allocated.add(ids.next());
        }

        assertThat(allocated).doesNotHaveDuplicates().isSorted();
    }

    /**
     * The reason the ids come from a sequence rather than an in-process counter: a second
     * detection instance on the same database must never be handed an id the first one holds.
     */
    @Test
    void aSecondAllocatorNeverOverlapsTheFirst() {
        List<Long> first = new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            first.add(ids.next());
        }

        FrameIdAllocator other = new FrameIdAllocator(jdbc);
        long theirs = other.next();

        // Not merely different — beyond everything the first allocator reserved, including the
        // rest of the block it has not handed out yet.
        assertThat(theirs).isGreaterThan(first.getLast());
        assertThat(first).doesNotContain(theirs);
    }
}
