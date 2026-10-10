# Spring Server Bootstrap

Shared Spring Boot startup wiring for server applications across the repository.
Classes live under `dev.orwell.bootstrap.{launch,auth,health,web,logging}`.

## Usage

Declare one `AppServer` per application and delegate `main` to it:

```java
public static final AppServer SERVER = new AppServer(
        MyApplication.class, "my-app", MyEnvs.ENV);

public static void main(String[] args) {
    SERVER.runOrExit(args);
}
```

`AppServerEnv` owns the common environment options and their Spring property mappings; apps add
their own options on top. The validated env is published as Spring properties by
`SpringServerBootstrap`.

| Env var | Spring property | Required |
|---|---|---|
| `SERVER_ADDRESS` | `server.address` | always |
| `SERVER_PORT` | `server.port` | always |
| `LOGGING_FILE_NAME` | `logging.file.name` | per-app (constructor flag) |
| `AUTH_BASE_URL` | `orwell.auth.base-url` | per-app (constructor flag) |
| `AUTH_WORKSPACE` | `orwell.auth.workspace` | optional |
| `LOGGER` | `orwell.logger.mode` | optional |
| `LOKI_URL` | `orwell.loki.url` | optional |
| `LOKI_TENANT_ID` | `orwell.loki.tenant-id` | optional |

`AUTH_WORKSPACE` names the auth-server workspace an app's identities live in; it is the first
path segment of every auth call. Unset, it is `def`, which is where identities created before
workspaces existed are — so an app that never sets it behaves exactly as it did.

`LOGGER` picks the sinks behind the `Logger` bean — `console`, `disk`, `loki`,
`loki-with-fallback` or `both`. An unknown value fails env validation naming the legal ones.
Unset, it stays what it always was: `loki` when `LOKI_URL` is set, `console` with a startup
warning when it is not. See `packages/logger/README.md` for what each mode costs.

### Why these are published as Spring properties

Direct constructor arguments are possible, but they would require manually plumbing configuration
through every component that needs it. They would also bypass Spring Boot's built-in server and
logging configuration, and make configuration precedence and testing less consistent. The
environment schema is responsible for validated, typed input; Spring's environment is the bridge
used to configure the running application.

The duplication between an env var name (`SERVER_PORT`) and a Spring property (`server.port`) is
intentional: the former is the external launcher configuration contract, the latter is Spring
Boot's internal one.

## Auto-configurations

Registered in `src/main/resources/META-INF/spring/…AutoConfiguration.imports` — keep that file
in sync if you move or rename these classes:

- `auth.AuthenticationStrategyConfiguration` — default `HttpAuthenticationStrategy` wired to
  `orwell.auth.base-url`, plus a request-scoped `AuthenticationContext`. Override by defining
  your own `AuthenticationStrategy` bean (`@ConditionalOnMissingBean`).
- `health.HealthEndpointConfiguration` — registers the shared `GET /health` endpoint
  (`SharedHealthController`) on every servlet app. Apps add response fields by contributing
  `HealthDetailsProvider` beans, or replace the endpoint by defining their own
  `SharedHealthController` bean. **`engine: spring` is stamped by the controller**, mirroring
  `UndertowHttp.health` on the other engine — an app must not add it itself. It goes on last, so a
  provider cannot claim an engine other than the one that ran it.
- `web.WebContractConfiguration` — the invalid-JSON 400 envelope (`InvalidJsonBodyAdvice`) and
  the `@RequireAuthentication` 401 guard.
- `logging.LoggerConfiguration` — the app-wide `Logger` bean, named from `orwell.app.name`. The
  default is `FailSafeLogger(CompositeLogger(ConsoleLogger, LokiLogger))` when `orwell.loki.url`
  is set, and `FailSafeLogger(ConsoleLogger)` with a startup warning when it isn't. **Servers do
  not write an app log file by default** — no file sink is involved. Override by declaring your
  own `Logger` bean (`@ConditionalOnMissingBean`).

## Maven dependency

```xml
<dependency>
    <groupId>dev.orwell</groupId>
    <artifactId>server-bootstrap</artifactId>
    <version>${project.version}</version>
</dependency>
```

## Key classes

| Class | Purpose |
|---|---|
| `launch.AppServer` | Per-app descriptor: `start(...)` / `runOrExit(args)` |
| `launch.AppServerEnv` | Common env options + Spring property mappings |
| `launch.SpringServerBootstrap` | Low-level Spring startup shell used by `AppServer` |
| `auth.RequireAuthentication` | 401-guard annotation for controllers/methods |
| `health.HealthDetailsProvider` | App hook to add fields to `GET /health` |
| `schedule.ScheduledJob` | Single-flight guard + run/alert/error counters for a scheduled service |
| `web.SharedJson` | Shared static `ObjectMapper` |

### `schedule.ScheduledJob`

For a service that runs a job on a schedule, can also be poked on demand, and reports what it has
been doing on `/health`. log-analyzer and liveness-analyzer are both that shape and had it written
out twice — the same `AtomicBoolean` single-flight, the same four counters, the same
`lastRunAt`/`lastAlertAt`/`lastError` triple, the same eight health keys.

```java
private final ScheduledJob job = new ScheduledJob("poll");   // -> pollsTotal, "A poll is already…"

@Scheduled(fixedRateString = "…")
void scheduledPoll() {
    job.runSafely(this::runPoll);          // a failure is recorded, the schedule survives
}

public Map<String, Object> pollOnce() throws IOException, InterruptedException {
    return job.runOnce(this::runPoll);     // busy envelope if one is already running
}

private Map<String, Object> runPoll(Instant startedAt) throws IOException, InterruptedException { … }
```

It is a collaborator, not a base class: neither service's run body has to be bent into a template
method to share the counters around it. The **noun** keeps each app's wire contract intact —
`"poll"` yields `pollsTotal`, `"check"` yields `checksTotal` — so adopting it changed no payload
either service already served. `runSafely` matters more than it looks: an exception escaping a
`@Scheduled` method cancels every future execution of that task, which is the quiet way one of
these services stops working altogether.
