# redis-client

A pooled, namespaced handle on the one shared Redis.

There is exactly one Redis in this repo (the `redis` service in `docker-compose.all-services.yml`),
so every app in it is a guest in the same keyspace. `RedisClient` makes that safe by construction:
it takes a **prefix** and puts it in front of every key it touches, so keeboarder's `ws:` and
insta's `insta:` can never collide no matter what key a caller passes.

```java
RedisClient redis = new RedisClient(host, port, "ws:");   // 2s connect timeout

redis.putHash("client:" + id, Map.of("name", name, "connectedAt", now));   // → ws:client:<id>
redis.addToSet("clients", id);                                            // → ws:clients
redis.getSetMembers("clients");
redis.set("scrape:nasa", json, Duration.ofHours(24));
redis.get("scrape:nasa");                                                  // Optional<String>
```

The prefix must not be blank — it is the only thing keeping one app's keys out of another's — and a
trailing `:` is added when missing, so `"ws"` and `"ws:"` mean the same thing.

## Why a prefix and not a database

Redis's own answer to "workspaces" is numbered logical databases (`SELECT n`), and they are the
wrong tool: they share one memory pool, one persistence file and one eviction policy, `FLUSHALL`
crosses all of them, connection-scoped selection means a pooled connection can land on the wrong
one, and Redis Cluster supports only database 0 — so choosing a database forecloses ever
clustering. This client therefore offers no database knob at all: everything is database 0, and the
prefix does the separating. A caller that truly needs another database can build its own
`JedisPool` and hand it in.

## Failure is the caller's call

Every command is wrapped, and a driver failure becomes a `RedisOperationException` — unchecked,
naming the command and the namespaced key. The client deliberately does not decide whether that is
fatal, because its callers disagree for good reasons:

- **insta's `RedisScrapeCache`** catches it and answers a miss. A cache outage should cost an Apify
  run, not availability.
- **keeboarder's `RedisClientCache`** lets it propagate. `ChatEndpoint` catches `RuntimeException`
  and tells the client its registration did not happen, rather than reporting a success Redis never
  recorded.

Neither policy could be imposed from inside the client without breaking the other.

## Connections

Each call borrows a connection from a `JedisPool` and returns it, so an instance is thread-safe and
cheap to share; a loop of commands pays one pool checkout each, which is a queue pop, not a new
socket. Construction opens no connection — the pool is lazy — so a client can be built before the
shared Redis is up. `close()` closes the pool; commands after that fail like any other outage.

## Commands

| Method | Redis |
|---|---|
| `get` / `set` / `set` with TTL / `delete` | `GET` / `SET` / `SETEX` / `DEL` |
| `putHash` / `getHash` / `getHashField` | `HSET` / `HGETALL` / `HGET` |
| `addToSet` / `removeFromSet` / `getSetMembers` | `SADD` / `SREM` / `SMEMBERS` |

Absent keys read back as `Optional.empty()`, an empty map, or an empty set — never `null`. Adding a
member twice or deleting an absent key is not an error, as in Redis itself.

Add commands here as apps need them, rather than reaching for Jedis directly: a call site holding
its own `JedisPool` is a namespace nobody else can see.

## Tests

`mvn -pl :redis-client -am test` — an ephemeral Testcontainers Redis on a random port, covering the
namespace, each command, and what a caller is told when Redis is unreachable. The repo's one-Redis
rule is about long-lived instances; a test must not depend on the shared stack being up.
