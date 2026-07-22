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
| `LOKI_URL` | `orwell.loki.url` | optional |
| `LOKI_TENANT_ID` | `orwell.loki.tenant-id` | optional |

`LOKI_URL` decides whether app logs ship at all: unset, the `Logger` bean falls back to
console-only and warns at startup. See `packages/logger/README.md`.

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
  `SharedHealthController` bean.
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
| `web.SharedJson` | Shared static `ObjectMapper` |
