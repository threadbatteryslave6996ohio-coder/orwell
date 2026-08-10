package dev.orwell.redis;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import redis.clients.jedis.Jedis;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The client against a real Redis: the namespace it writes into, the commands its callers need,
 * and what a caller is told when Redis is not there.
 *
 * <p>This uses an ephemeral Testcontainers Redis on a random port — the repo's one-Redis rule is
 * about long-lived instances, and a test must not depend on the shared stack being up.
 */
@Testcontainers
class RedisClientTest {

    @Container
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private final List<RedisClient> opened = new ArrayList<>();

    @AfterEach
    void closeClients() {
        opened.forEach(RedisClient::close);
        opened.clear();
        try (Jedis jedis = jedis()) {
            jedis.flushAll();
        }
    }

    @Test
    void storesAndReadsBackAValue() {
        RedisClient client = client("app:");

        client.set("greeting", "hello");

        assertThat(client.get("greeting")).contains("hello");
    }

    @Test
    void missesOnAKeyItNeverWrote() {
        assertThat(client("app:").get("nothing")).isEmpty();
    }

    /**
     * The prefix is the whole answer to sharing one Redis between apps. If it silently disappeared,
     * two of them would be free to collide on a key as ordinary as {@code clients}.
     */
    @Test
    void putsThePrefixInFrontOfEveryKey() {
        client("app:").set("greeting", "hello");

        try (Jedis jedis = jedis()) {
            assertThat(jedis.keys("*")).containsExactly("app:greeting");
            assertThat(jedis.exists("greeting")).isFalse();
        }
    }

    @Test
    void keepsTwoPrefixesOutOfEachOthersKeys() {
        RedisClient first = client("one:");
        RedisClient second = client("two:");

        first.set("shared-name", "first");
        second.set("shared-name", "second");

        assertThat(first.get("shared-name")).contains("first");
        assertThat(second.get("shared-name")).contains("second");
    }

    /** A prefix is a namespace, and {@code appclients} is not one — so the separator is added. */
    @Test
    void addsTheSeparatorToAPrefixThatIsMissingOne() {
        RedisClient client = client("app");

        assertThat(client.prefix()).isEqualTo("app:");

        client.set("greeting", "hello");
        try (Jedis jedis = jedis()) {
            assertThat(jedis.keys("*")).containsExactly("app:greeting");
        }
    }

    @Test
    void refusesToRunWithoutAPrefix() {
        assertThatThrownBy(() -> new RedisClient("localhost", 6379, "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void expiresAValueStoredWithATimeToLive() {
        RedisClient client = client("app:");

        client.set("greeting", "hello", Duration.ofHours(24));

        try (Jedis jedis = jedis()) {
            assertThat(jedis.ttl("app:greeting"))
                    .isPositive()
                    .isLessThanOrEqualTo(Duration.ofHours(24).toSeconds());
        }
    }

    @Test
    void deletesAKey() {
        RedisClient client = client("app:");
        client.set("greeting", "hello");

        client.delete("greeting");

        assertThat(client.get("greeting")).isEmpty();
        assertThatCode(() -> client.delete("greeting")).doesNotThrowAnyException();
    }

    @Test
    void writesAndReadsHashFields() {
        RedisClient client = client("app:");

        client.putHash("client:1", Map.of("name", "laptop", "connectedAt", "now"));

        assertThat(client.getHash("client:1"))
                .containsExactlyInAnyOrderEntriesOf(Map.of("name", "laptop", "connectedAt", "now"));
        assertThat(client.getHashField("client:1", "name")).contains("laptop");
        assertThat(client.getHashField("client:1", "missing")).isEmpty();
    }

    @Test
    void answersAnAbsentHashWithAnEmptyMap() {
        assertThat(client("app:").getHash("client:nobody")).isEmpty();
    }

    @Test
    void addsAndRemovesSetMembers() {
        RedisClient client = client("app:");

        client.addToSet("clients", "one");
        client.addToSet("clients", "two");
        client.addToSet("clients", "two");

        assertThat(client.getSetMembers("clients")).containsExactlyInAnyOrder("one", "two");

        client.removeFromSet("clients", "one");

        assertThat(client.getSetMembers("clients")).containsExactly("two");
    }

    @Test
    void answersAnAbsentSetWithAnEmptySet() {
        assertThat(client("app:").getSetMembers("nobody")).isEmpty();
    }

    /**
     * A caller has to be able to tell an empty answer from an unreachable store — a cache calls
     * that a miss, a client registry calls it a failed registration, and only the caller knows.
     */
    @Test
    void reportsAFailureRatherThanAnEmptyAnswerWhenRedisIsUnreachable() {
        // Port 1 is reserved and refuses connections, standing in for Redis being down.
        RedisClient client = new RedisClient("127.0.0.1", 1, "app:");
        opened.add(client);

        assertThatThrownBy(() -> client.get("greeting"))
                .isInstanceOf(RedisOperationException.class)
                .hasMessageContaining("GET")
                .hasMessageContaining("app:greeting");
        assertThatThrownBy(() -> client.set("greeting", "hello"))
                .isInstanceOf(RedisOperationException.class);
    }

    private RedisClient client(String prefix) {
        RedisClient client = new RedisClient(REDIS.getHost(), REDIS.getMappedPort(6379), prefix);
        opened.add(client);
        return client;
    }

    private static Jedis jedis() {
        return new Jedis(REDIS.getHost(), REDIS.getMappedPort(6379));
    }
}
