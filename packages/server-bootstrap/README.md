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

`AppServerEnv` owns the common environment options (`SERVER_ADDRESS`, `SERVER_PORT`,
`LOGGING_FILE_NAME`, `AUTH_BASE_URL`) and their Spring property mappings; apps add their own
options on top. The validated env is published as Spring properties by
`SpringServerBootstrap`.

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
- `logging.LoggerConfiguration` — the app-wide `CustomLogger` bean named from
  `orwell.app.name`.

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
