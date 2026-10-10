# logger

The repo-wide logging service. Every module logs through the `dev.orwell.logging.Logger`
interface; nothing writes to `System.out` or `System.err` directly, so the sink behind any call
site can be swapped without touching the call site.

## The interface

`Logger` has one abstract method, `log(LogEntry)`, plus `default` conveniences:

```java
logger.info("Sent clipboard change.", Map.of("clientId", clientId, "chars", content.length()));
logger.warn("Cannot reach remote server.", Map.of("endpoint", endpoint));
logger.error("Clipboard send failed.", metadata);
```

Because there is exactly one abstract method, a test double is a lambda:

```java
List<LogEntry> logged = new ArrayList<>();
var controller = new ClipboardEntryController(repository, provider, logged::add);
```

Put structured values in the **metadata map**, not in the message. The message should be a
constant sentence; the varying parts are fields. Metadata values may be null — `exception
.getMessage()` is null for plenty of exception types, and a logging call must never be the thing
that takes its caller down.

## Sinks

| Sink | Output | Use for |
|---|---|---|
| `ConsoleLogger` | stdout, with WARN/ERROR to stderr | desktop clients, standalone `main`s, the debug half of the server default |
| `JsonLogger` | one JSON object per line to a file | anything machine-read from disk |
| `LokiLogger` | async batched push to Loki | the shipped half of the server default |
| `CustomLogger` | `<log dir>/<name>.txt` | a durable human-readable file record |
| `CompositeLogger` | fans out to several sinks | combining the above |
| `FailSafeLogger` | contains sink failures | wrapping any sink used from a request path |

Which of them a **server** gets is configuration, not code — see [Choosing sinks with
`LOGGER`](#choosing-sinks-with-logger) below.

Text sinks render as `<timestamp> [name] [LEVEL] message key=value`. Values containing
whitespace, `=`, or a quote are quoted, so a field like `error="Connection refused: connect"`
survives as one field. Metadata keys are sorted, so the same entry always renders identically —
do not rely on insertion order reaching the output.

File sinks resolve their directory through `LogFiles`, which reads the `custom.logger.dir` system
property. On a server the bootstrap derives that from `logging.file.name`, so the app's own file
lands beside Spring's log rather than in `logs/` — `logs/` is only the fallback when nothing set
the property (typically a standalone `main`).

`JsonLogger` emits `timestamp`, `level`, `message`, then metadata flattened alongside them.
Metadata keys colliding with those three reserved names are dropped rather than allowed to
overwrite them.

## Choosing sinks with `LOGGER`

One environment variable decides what a process logs to. `LoggerSetup` turns it into the sink
tree; `LoggerMode` is the enum of legal values.

| `LOGGER` | Sinks | For |
|---|---|---|
| `console` | console | local development; the default when no `LOKI_URL` is set |
| `disk` | console + `<log dir>/<app>.jsonl` | a collector that tails files, or logs that must survive without a network |
| `loki` | console + Loki | the default when `LOKI_URL` is set — what every server did before this key existed |
| `loki-with-fallback` | console + Loki, disk catching **only what Loki refused** | when losing records to a Loki outage is unacceptable |
| `both` | console + Loki + disk, every record to each | two complete copies, at the price of a disk write per call |

Three things hold across all of them:

- **The console is always attached.** stdout is what `docker logs` reads, and no configuration
  should be able to make a container silent. So `LOGGER` names what happens *in addition* to the
  console, and `console` is the mode that adds nothing.
- **`loki-with-fallback` is not `both`.** The fallback file gets only the entries Loki did not
  take — a full queue, a failed push, an error response — so it reads as an outage record rather
  than a duplicate of everything, and a healthy Loki costs no disk I/O at all. The cost: while
  Loki is failing, the caller may pay for the fallback's disk write, which is precisely what
  asking for a fallback means.
- **Leaving `LOGGER` unset changes nothing.** The default is `loki` when `LOKI_URL` is set and
  `console` when it is not — the behavior every server had before the key existed.

Case and `_` vs `-` are forgiven (`LOKI_WITH_FALLBACK` works). Anything else fails at startup
naming the legal values, because a typo that quietly became the default would ship nothing while
looking configured.

Two failure rules, drawn deliberately in different places:

- A **contradiction between configured values** — `LOGGER=loki` with no `LOKI_URL` — fails
  startup. It costs nothing to detect, and the alternative is a deployment that believes it is
  shipping logs and is not.
- A **sink that will not open** — an unwritable log file — is reported loudly on the console and
  skipped. Logging must not be why a server refuses to boot, and the records still reach the
  sinks that work.

The file sinks write `<log dir>/<app>.jsonl`, with the directory resolved by `LogFiles` as
described above — set `LOGGING_FILE_NAME` to move it. There is no separate key for the log file.

## Wiring

**Spring servers** get a `Logger` bean automatically from `packages/server-bootstrap`
(`LoggerConfiguration`, registered in that module's `AutoConfiguration.imports`). Just declare
`Logger` as a constructor parameter. To override, declare your own `Logger` bean;
`@ConditionalOnMissingBean` steps aside. `LOGGER` is a common key on `AppServerEnv`, so no app
declares it.

Whatever the mode, the shape is the same:

```
FailSafeLogger( CompositeLogger( ConsoleLogger,   → stdout, human, quick debug
                                 JsonLogger,      → disk / both
                                 LokiLogger ) )   → loki / loki-with-fallback / both
```

`LokiLogger` never blocks its caller. `log()` does a non-blocking offer onto a bounded queue and
returns; one daemon thread batches and ships. When the queue is full — Loki down, slow, or a
burst — entries are **dropped and counted** rather than queued at the cost of a request thread,
or handed to the fallback sink if one is configured. The buffer is in memory, so entries queued
but unshipped are lost if the process dies; that is the accepted cost of pushing directly instead
of writing a file for a collector to tail, and `loki-with-fallback` is the answer where that cost
is too high.

`FailSafeLogger` is not optional decoration: controllers log unguarded inside request paths, so
without it a full disk turns a valid login into an HTTP 500. Sink failures are contained and
reported once to stderr.

**Standalone `main`s** call the same factory rather than rebuilding the tree by hand — this is
what `jarvis-person-detection`, `jarvis-retention-worker` and `jarvis-frame-viewer` do, so they
answer to `LOGGER` exactly like the Spring servers:

```java
Logger logger = LoggerSetup.fromConfiguration(
        "jarvis-retention-worker",
        env.get(RetentionWorkerEnvs.LOGGER),
        env.get(RetentionWorkerEnvs.LOKI_URL),
        env.get(RetentionWorkerEnvs.LOKI_TENANT_ID));
```

It takes plain strings, never an `Env` — this module stays free of the env framework, so tests and
desktop clients can call it with literals. What comes back is a `ManagedLogger`: a `Logger` to
every call site, plus the `close()` that flushes `LokiLogger`'s queue on shutdown. Spring calls it
via the bean's `destroyMethod`; a `main` can use try-with-resources or a shutdown hook. Before it
existed the Loki sink was unreachable inside the composite, so nothing ever flushed it.

**Clients and standalone `main`s** have no context to inject from: construct one `ConsoleLogger`
named after the artifactId in `main` and pass it down through constructors.

```java
Logger logger = new ConsoleLogger("klippy-mac-client");
```

Three places construct a sink instead of receiving one. Two are legitimate:

- `AnalysisWorker` — a standalone `main`, so there is nothing to inject from. Types the variable
  as `Logger`.
- `AlertService` (`apps/alerting`) — `new JsonLogger(...)` at `${alert.log-file}`. This is a
  second, deliberately separate sink: the alert trail is its own on-disk contract, not the
  app-wide `Logger` bean. Held as `Logger` so the sink stays swappable.

The third is the known outlier, not a pattern to copy: `PollInterval`
(`apps/klippy/clients/client-core`) holds a `private static final CustomLogger LOGGER` — static,
and typed as the concrete sink rather than as `Logger`, so its sink cannot be swapped or
substituted in a test. It is called out as an outlier in the root `CLAUDE.md`; new code passes a
`Logger` in.

## The two log categories

This repo distinguishes them, and they are collected differently:

- **Infra logs** — container/systemd/nginx stdout, kernel, framework prose from
  Spring/Hibernate/Tomcat. Unstructured, scraped host-wide by the existing agent. Nobody writes
  these deliberately, and nothing in this repo ships them.
- **App logs** — what `dev.orwell.logging.Logger` produces. Structured, semantically meaningful,
  and the input `apps/log-analyzer` reasons over.

Keeping them apart is what direct push buys. Only entries that went through `Logger` reach Loki,
labelled `stream_type="app"`, so the app stream is structured by construction and never
interleaved with framework prose. stdout still carries both, and stays useful for a quick
`docker logs` without being anybody's ingestion path.

App logs go straight to Loki from the JVM by default; there is no collector in the picture. A
`.jsonl` file appears only where `LOGGER` asks for one (`disk`, `both`, or an outage under
`loki-with-fallback`).

### A second stream, for records the app stream should not carry

`stream_type` is a **label**, which is what a consumer selects on — `apps/log-analyzer` queries
`{stream_type="app"} | json | level="ERROR"`, and a selector on a *field* would make Loki read every
stream to find it. It takes a handful of values across the whole deployment, which is what labels are
for; per-user or per-request values would not be (see the cardinality table in
[`apps/log-analyzer/README.md`](../../apps/log-analyzer/README.md)).

Everything a `Logger` produces is `stream_type="app"`. One case wants its own:

```java
// A heartbeat channel: Loki-only, its own stream, empty when LOKI_URL is unset.
Optional<ManagedLogger> heartbeat =
        LoggerSetup.heartbeatChannel("heartbeat", appName, lokiUrl, lokiTenantId);
```

**Use it for records that are high-volume and low-information**, where mixing them into the app stream
would swamp the thing that reads it. A liveness heartbeat is the case it exists for: one line per
interval per process is thousands a day, all of them saying "still here", and the app stream is what
gets triaged for errors. `apps/health-monitor` publishes one so that
[`apps/liveness-analyzer`](../../apps/liveness-analyzer/README.md) can alert on its *absence* — the two
apps then cover each other.

Three properties of that channel are deliberate:

- **Loki-only, no console sink.** A heartbeat exists to be absent, and a copy on stdout would put the
  same thousands of lines in `docker logs` where nobody is watching for a gap.
- **No fallback file.** If Loki cannot be reached the heartbeat does not arrive and the dead-man's
  switch fires — which is correct, because nobody can currently verify the process is doing its job. A
  file recording it locally would make the switch fire while claiming the opposite on disk.
- **Still wrapped in `FailSafeLogger`.** A heartbeat that threw would take down the scheduled work it is
  evidence of.

Everything else about it is an ordinary `LokiLogger`: same batching, same bounded queue, same
never-blocks-the-caller guarantee. `LokiLogger.onStream` validates the label value — letters, digits,
`_` and `-` only, because a value with a quote in it produces a stream nothing can write a selector
for. The identity a consumer groups by (`clientId`) belongs in the line **body**, not in a label. See [`apps/log-analyzer/README.md`](../../apps/log-analyzer/README.md) for
the label scheme.

**The name you pass here becomes a query dimension.** `orwell.app.name` becomes Loki's `app`
label directly, so renaming an app renames a label that dashboards and alerts select on. That is
the string-rename hazard the root `CLAUDE.md` warns about: renaming a Java identifier is free, renaming a string is someone else's contract.
The `${orwell.app.name:app}` fallback is the same hazard in miniature — a service that fails to
publish a name lands in Loki as `app="app"`.

## Note on scheduled work

`scheduleWithFixedDelay` cancels a task permanently the first time it throws. Any polling loop
must contain throwables from its own body — including from the logging sink — or a single failed
log call silently ends the loop for the life of the process. See
`DesktopClientRunner.pollSafely`.

## Build

```bash
mvn -pl :logger -am test
```
