# jarvis-retention

Keeps an append-only table inside a budget. A library, not a service — `jarvis-detection` depends
on it and drives the sweep from its own scheduler.

```java
RetentionPolicy policy = RetentionPolicy.of(
        "frame_events", "id", "captured_at", "frame_bytes",
        2L * 1024 * 1024 * 1024,       // keep at most 2 GiB of payload
        Duration.ofMinutes(5));        // and nothing older than 5 minutes

SweepResult result = new RetentionSweeper(dataSource, logger).sweep(policy, Instant.now());
```

## Two bounds, whichever bites first

- **`maxBytes`** — a budget on the payload currently stored. This is the bound that matters when a
  table holds large values, because it is the one that tracks the cost. Under it, the *history* a
  table keeps is variable and its *footprint* is constant, which is the right way round when the
  table is a replay buffer.
- **`maxAge`** — an upper bound on how stale a retained row may be. On a quiet table the byte
  budget alone would keep rows forever; this is what stops that.

Either can be disabled (null, or non-positive). The age bound is applied first: dropping stale rows
is usually enough on its own, and doing it first leaves the byte trim with less to do.

## Three things it does deliberately

**It counts payload bytes, not table size.** The budget is measured as
`SUM(octet_length(payloadColumn))`. The obvious alternative, `pg_total_relation_size`, is a trap:
deleting rows does not return space to the filesystem — only `VACUUM FULL` does, and it needs an
exclusive lock and room for a full copy. A loop that trimmed until the *relation size* fell would
never terminate and would empty the table. `RetentionSweeperTest` pins this behaviour so nobody
"fixes" the measurement back.

`octet_length` on a TOASTed value is answered from the pointer's stored raw size, so the sum reads
the heap without pulling a single payload back out of TOAST. That is what makes it cheap enough to
run every sweep.

The number is therefore payload, not footprint: indexes, row overhead and not-yet-vacuumed dead
rows all cost disk on top of it. Set the budget comfortably below the disk you are willing to
spend.

**It deletes in batches, each its own transaction.** Removing a large backlog in one statement is a
long transaction, one enormous WAL burst and a vacuum storm afterwards — all while the writer
feeding the table is still queuing. `batchSize` (default 500) caps how much any single transaction
takes, so a sweep that fails partway leaves a smaller, consistent table rather than rolling
everything back.

**A byte trim removes exactly what is over the target, never a fixed batch.** Each iteration walks
the running total of payload sizes over the oldest rows and stops at the row that crosses the
excess. A flat `batchSize` delete would overshoot by most of a batch whenever the overshoot is
small — 500 rows discarded to get 20 rows under budget. The batch size stays the ceiling, so a
large trim is still many small transactions.

Trimming stops at `trimToFraction` of the budget (default 0.9) rather than exactly at it, so a
table sitting on its limit does not delete on every single sweep.

## Transactions, and why there is no Spring here

The sweeper takes a `javax.sql.DataSource` and owns its transactions through the JDBC connection.
That is not an oversight:

A `@Transactional` method that is package-private, or called from another method on the same bean,
is silently not advised by Spring's proxy. The bulk delete then throws `No active transaction for
update or delete query`, whatever wrapper is around it logs a warning, and retention *appears* to
run forever while deleting nothing. That is not hypothetical — it is the bug this module was
extracted to fix, and it survived because the failure is a log line rather than a crash.

Owning the connection makes that failure mode unreachable, and has two side benefits: the sweeper
works under a non-Spring engine, and it is testable against a bare Testcontainers Postgres with no
application context.

`sweep` throws `SQLException` rather than swallowing it, so a caller on a scheduler can decide what
a failed sweep is worth — but cannot fail to notice one.

## Build and test

```bash
mvn -pl :jarvis-retention -am test
```

Tests run against a real Postgres via Testcontainers, because every behaviour worth checking here
is a property of the database rather than of the Java.
