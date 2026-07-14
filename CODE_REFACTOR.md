# Refactor backlog

Remaining extraction/cleanup work, ordered by value. (Earlier items — the apps/packages split,
the Spring migration of all servers, the `AppServer.spring()` descriptor, shared
logger/health/auth/JSON auto-configs, the invalid-JSON `@RestControllerAdvice`, the
`@RequireAuthentication` guard, the shared Testcontainers base, and the removal of
combined-server — are done.)

## 1. Common `springProperties` keys → `AppServer` descriptor

Every `*Envs` class still hand-maps the same universal keys: `server.port` (10 copies),
`server.address` (6 copies — and auth lacks one, so the auth server always binds `0.0.0.0`),
`logging.file.name` (3), and the auth base-url (4 copies under **three different property
names**: `clippy.auth.base-url`, `secrets.auth.base-url`, `proxy.auth-server.base-url`).

Proposed: the descriptor owns the common keys, `springProperties` keeps only app-specific ones.

```java
AppServer.spring(App.class)
        .name("analyzer")
        .envs(AnalyzerEnvs.ENV)
        .port(ANALYZER_PORT).address(ANALYZER_HOST)   // -> server.port / server.address
        .authBaseUrl(AUTH_SERVER_URL)                  // -> clippy.auth.base-url (one canonical key)
        .loggingFile(LOGGING_FILE_NAME)                // -> logging.file.name + CustomLogger directory
        .properties(AnalyzerEnvs::springProperties)
        .build();
```

Sub-items:
- Canonicalize the auth base-url property to one key so `AuthenticationStrategyConfiguration`
  no longer needs a hardcoded default.
- Collapse the `loggingFile` double-declaration (today auth/klippy/secrets pass the same env
  value to both `.loggingFile(...)` and `springProperties`'s `logging.file.name`): the
  bootstrap should read `logging.file.name` from the assembled properties and configure the
  `CustomLogger` directory from it, deleting the builder branch.
- Add the missing `AUTH_SERVER_HOST` option while at it.

## 2. `spring-boot-maven-plugin` block → `server-parent` pluginManagement

12 poms repeat the identical plugin block (repackage + `exec` classifier), differing only in
`mainClass`. The plugin natively resolves the `start-class` property, so the parent can own the
block and each app pom shrinks to `<properties><start-class>…</start-class></properties>`.

Related: `apps/auth/pom.xml` and `apps/secrets-manager/pom.xml` have **no parent** and
re-declare the Spring BOM + compiler/surefire pluginManagement. Point them at the root (or
`server-parent`) and delete the duplicated management sections.

## 3. Smaller cleanups

- **`sha256Hex` triplication**: byte-for-byte copies in `DetectionService` and
  `AnalysisWorker` (producer/consumer of the same frame-hash contract — divergence breaks every
  frame with "frame hash mismatch"), plus a third digest impl in `LogAnalyzerService.fingerprint`.
  Hoist one helper into `packages/primitives`.
- **Detection alert client**: `DetectionService.detect()` hand-builds its alert POST (no
  timeout, no blank-URL guard, no transient/terminal outcome split) while `log-analyzer`'s
  `AlertClient` already encapsulates all of that for the same `/alerts` endpoint. Promote
  `AlertClient` to a shared module and use it from both. Add connect/request timeouts either way.
- **Dead `from(Map)` wrappers**: `AnalyzerEnvs`/`GmailEnvs`/`AlertEnvs`/`LogAnalyzerEnvs` each
  ship a zero-caller `from(Map)` alias; delete them (callers can use `X.ENV.from(map)`).
- **Orphaned test**: `ClippyServerLauncherTest` is named after a deleted class and only tests
  `EnvFiles.load` (already covered in `packages/env`); delete or move the assertion.
- **`GmailService.gmail()` 401-retry** rebuilds the identical `HttpRequest` twice; extract a
  `request(url, body, token)` helper so the primary and retry paths cannot drift.
- **Gmail webhook auth**: `GmailService.save()` still hand-attaches auth headers; the
  authenticated-client pattern (`ClientAuthSession`-style token cache with 401 refresh) exists in
  klippy's client-core and is also re-implemented in `SecretsManagerClient`/`BucketProxyClient`.
  One shared authenticated-HTTP helper would replace four variants.
- **`KeeboarderWebSocketRuntime`** reduces to a
  `@Bean(destroyMethod = "close") @ConditionalOnBooleanProperty RedisClientCache` whose bean
  method calls `ChatEndpoint.initialize(...)` — the holder class and its mutable state disappear.
- **`AppServer.Builder.build()`** copies its fields into shadow locals before lambda capture;
  capture the fields directly.
- **Logger fallback name**: `LoggerConfiguration` defaults to `"app"` when `orwell.app.name` is
  missing (a context booted outside the descriptor logs to the wrong stream silently). Consider
  failing fast instead, or deriving from `spring.application.name`.
- **Route prefixes**: with combined-server gone, only keeboarder's `/api` prefix is real. The
  remaining `${x.server.route-prefix:}` placeholders (klippy, auth, secrets, proxy) should either
  be deleted like the migrated apps' were, or generalized to `server.servlet.context-path` at the
  descriptor level so `/health` moves together with app routes.
- **`SharedHealthController` allocations**: providers build intermediate maps copied into the
  response on every poll; a `contribute(Map target)` signature would avoid the churn.
- **dev-stack startup**: the five JVMs start sequentially (sum of boot times); launch all five
  first, then poll all health URLs (max of boot times).
- **Adopt `@RequireAuthentication` in the older apps**: klippy/keeboarder/proxy still hand-roll
  their 401 responses (different bodies: Spring default error JSON, empty body,
  `{"status":"unauthorized"}`). Adopting the shared guard means aligning those response
  contracts first; klippy/proxy also do clientId-match checks that stay in the controller.
