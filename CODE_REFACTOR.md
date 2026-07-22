# Refactor backlog

Remaining extraction/cleanup work, ordered by value. (Earlier items — the apps/packages split,
the Spring migration of all servers, the `AppServer.spring()` descriptor, shared
logger/health/auth/JSON auto-configs, the invalid-JSON `@RestControllerAdvice`, the
`@RequireAuthentication` guard, the shared Testcontainers base, the removal of combined-server,
the orphaned launcher test, the klippy naming pass, and the `Logger`-service migration — are
done. Also done since: the common `springProperties` keys now live on the `AppServerEnv`
descriptor rather than in each `*Envs` class — documented in
[`packages/server-bootstrap/README.md`](packages/server-bootstrap/README.md); the dead `from(Map)`
wrappers are gone from `AnalyzerEnvs`/`GmailEnvs`/`AlertEnvs`/`LogAnalyzerEnvs`; and the "two
logging facades" split is resolved — there is no app-local `JsonLogger` in `apps/alerting`, which
imports `dev.orwell.logging.JsonLogger`.)

## 0. Operational notes from the naming pass

Not backlog items, but worth knowing:

- **The Android module is not in the Maven reactor.** It builds with Gradle, so `mvn test` proves
  nothing about it. Changes there are unverified by CI here and want a real Gradle build.
- **DEPLOY ACTION — servers no longer write an app log file at all.** The Spring default sink is
  console + Loki push. Any external log rotation, volume mount, or shipping config keyed to
  `<app>.txt` or `<app>.jsonl` now watches a file nobody writes. `CustomLogger` still writes
  `.txt` where it is used directly (`EnvSnapshotLogger`, `PollInterval`).

## 1. `spring-boot-maven-plugin` block → `server-parent` pluginManagement

12 poms repeat the identical plugin block (repackage + `exec` classifier), differing only in
`mainClass`. The plugin natively resolves the `start-class` property, so the parent can own the
block and each app pom shrinks to `<properties><start-class>…</start-class></properties>`.

Related: `apps/auth/pom.xml` and `apps/secrets-manager/pom.xml` have **no parent** and
re-declare the Spring BOM + compiler/surefire pluginManagement. Point them at the root (or
`server-parent`) and delete the duplicated management sections.

## 2. Smaller cleanups

- **`sha256Hex` residual**: `packages/primitives` `Sha256` now exists and both `DetectionService`
  and `AnalysisWorker` use `Sha256.hex`. Only `LogAnalyzerService.fingerprint`
  (`LogAnalyzerService:315-317`) still builds its own `MessageDigest` — that one is a private
  fingerprint rather than a shared contract, so switching it is tidiness, not correctness.
- **Nothing alerts when log shipping stops**: if `LOKI_URL` is unset or Loki is unreachable,
  `LokiLogger` drops and counts while every service stays healthy and `log-analyzer` sees zero
  errors — indistinguishable from a clean stack. Drops go to stderr on an interval, which beats
  silence but is not an alert. Wanted: a deadman check that the stream is still receiving.
- **Detection alert client**: `DetectionService.detect()` hand-builds its alert POST (no
  timeout, no blank-URL guard, no transient/terminal outcome split) while `log-analyzer`'s
  `AlertClient` already encapsulates all of that for the same `/alerts` endpoint. Promote
  `AlertClient` to a shared module and use it from both. Add connect/request timeouts either way.
- **Gmail webhook auth**: `GmailService.deliver()` still hand-attaches auth headers; the
  authenticated-client pattern (`ClientAuthSession`-style token cache with 401 refresh) exists in
  klippy's client-core and is also re-implemented in `SecretsManagerClient`/`BucketProxyClient`.
  One shared authenticated-HTTP helper would replace four variants.
- **`KeeboarderWebSocketRuntime`** reduces to a
  `@Bean(destroyMethod = "close") @ConditionalOnBooleanProperty RedisClientCache` whose bean
  method calls `ChatEndpoint.initialize(...)` — the holder class and its mutable state disappear.
- **Logger fallback name**: `LoggerConfiguration` defaults to `"app"` when `orwell.app.name` is
  missing (a context booted outside the descriptor logs to the wrong stream silently). Consider
  failing fast instead, or deriving from `spring.application.name`.
- **Client logging stops at the console**: the klippy clients now build a `ConsoleLogger` from
  `dev.orwell.logging` in `main`, but no client ships anything to Loki, so client-side failures
  are invisible to `log-analyzer` while every server is covered. Add the `LokiLogger` sink to
  client logging (composed behind `FailSafeLogger`, as the servers do), gated on `LOKI_URL`.
- **Route prefixes**: klippy's placeholder is gone. Four `${x.server.route-prefix:}` placeholders
  remain — auth (`orwell.auth.route-prefix`), secrets (`secrets.route-prefix`, on both the admin
  and accessor controllers), the jarvis proxy (`jarvis.server.route-prefix`), and keeboarder
  (`keeboarder.server.route-prefix`). Keeboarder's `/api` is the only one carrying a non-empty
  value in practice; the other three default to empty and are unused. Either delete them as
  klippy's was, or generalize to `server.servlet.context-path` at the descriptor level so
  `/health` moves together with app routes.
- **`SharedHealthController` allocations**: providers build intermediate maps copied into the
  response on every poll; a `contribute(Map target)` signature would avoid the churn.
- **Stream-worker hardening (from the streaming/proxy unification review)**: four low-severity
  items in `dev.orwell.bucket.proxy.streaming.AnalysisWorker` and `scripts/analyze_stream.sh`.
  None are correctness regressions — they only bite on pathological or large-frame input the
  default 640px MJPEG pipeline does not produce.
  - *Oversize-frame guard discards the whole buffer instead of resyncing*: in `pollFrame()`, the
    `size - frameStart > MAX_FRAME_BYTES` (32 MB) branch calls `compact(size)`, dropping the
    entire buffer — so a stray `0xFFD8` with no matching `0xFFD9` takes any valid complete frames
    within that window down with it. `compact(frameStart + 2)` would resync to the next SOI.
  - *`buf` never shrinks*: `ensureCapacity()` doubles but nothing shrinks, so one large/corrupt
    frame pins ~64 MB for the worker's whole (continuously running) lifetime. Shrink back toward
    the initial size when `size` falls well below `buf.length`. At width 640 `buf` stays ~64 KB,
    so this never triggers today.
  - *Potential busy-spin on `read() == 0`*: the iterator's `hasNext()` appends nothing and never
    sets `finished` for a 0-byte read, so a stream returning 0 forever spins at 100% CPU. Bound
    or guard that case. Theoretical — stdin pipes and `ByteArrayInputStream` never return 0.
  - *`set -u` + empty override array on bash < 4.4*: `"${WORKER_MODE_ARGS[@]}"` in
    `analyze_stream.sh` errors when empty. Latent only — the
    `${STREAM_ANALYSIS_WORKER_MODE:---mode=stream-worker}` default keeps it non-empty. Revisit if
    that default is ever removed.
- **Auth-server hardening (moved out of `apps/auth/http-based/server/README.md`, where it had
  accumulated as a code-review dump in user-facing docs)**: none of these are live bugs, but all
  are real.
  - *Controllers depend on OSIV*: `login`, `createIdentity`, and `checkToken` carry no
    `@Transactional`, so entities handed between repository calls (a `ClientIdentity` from
    `findByClientId` into `new ClientToken`) stay attached only because Spring Boot enables Open
    Session In View by default. Disabling OSIV breaks them with detached-entity errors.
  - *No rate limiting on `/login`*: 120k PBKDF2 iterations per request with no throttle, lockout,
    or backoff — brute-force and DoS amplification in one endpoint. The highest-value item here.
  - *Unbounded `token` input*: `CheckTokenHttpRequest.token` has `@NotBlank` but no `@Size`, so a
    multi-megabyte token reaches SHA-256 and the database as a query parameter.
  - *`CredentialHasher.matches()` does not handle corrupt stored hashes*: a malformed hash raises
    `NumberFormatException`/`IllegalArgumentException` and surfaces as a 500 rather than a 401.
  - *`PBEKeySpec` secret never cleared*: `CredentialHasher.pbkdf2` needs a `finally` calling
    `clearPassword()`; today the secret char array lingers on the heap.
  - *TOCTOU in `createIdentity`*: `existsByClientId` before `save` races, and the redundant
    round-trip is unnecessary — the `UNIQUE` constraint plus the existing
    `DataIntegrityViolationException` catch already covers it.
  - *Timing side-channel on inactive identities*: the `isActive` check precedes PBKDF2, so
    inactive accounts reject measurably faster and account status leaks remotely.
  - *Test gaps*: the `DataIntegrityViolationException` path is never exercised; nothing asserts
    `tokens.save()` is skipped on failed login; there is no token-not-found test for `checkToken`;
    `CredentialHasherTest`/`TokenGeneratorTest` hardcode `120000` and `43` instead of referencing
    the source constants; and real 120k-iteration PBKDF2 makes the unit tests slow.
- **Adopt `@RequireAuthentication` in the older apps**: klippy/keeboarder/proxy still hand-roll
  their 401 responses (different bodies: Spring default error JSON, empty body,
  `{"status":"unauthorized"}`). Adopting the shared guard means aligning those response
  contracts first; klippy/proxy also do clientId-match checks that stay in the controller.

## 3. Live duplication (folded in from the retired removing-redundant-code.md)

- **Two cooldown-tracker variants**: `apps/jarvis/detection/.../CooldownTracker.java` and
  `apps/log-analyzer/.../AlertCooldownTracker.java` implement the same concept with different
  code — log-analyzer's has evolved reservation semantics, jarvis's has not. (The third copy that
  used to live in `apps/alerting` is gone; that app has no cooldown tracker.) Extract one shared
  implementation, starting from log-analyzer's reservation semantics.
- **Secrets-manager DTO triplication**: the same response shapes exist as admin records,
  accessor records, and client records
  (`apps/secrets-manager/server/.../admin/*Response.java`, `accessor/*Response.java`,
  `apps/secrets-manager/client/.../dto/*.java`); the create/update request records also pair up
  near-identically. Merge into shared records.
- **Maven shade plugin declared unconfigured in 5 klippy client poms**
  (linux/mac/dummy/offline-sync/file-locker); declare once in a parent `pluginManagement`.
- **Dead code**: `packages/primitives/.../Flag.java` has zero consumers — delete it.
- **Unguarded auto-config registry**: nothing tests that the four entries in server-bootstrap's
  `META-INF/spring/...AutoConfiguration.imports` resolve; a typo silently drops the shared
  `/health` endpoint, 401 guard, logger, and auth-strategy beans. Add a `@SpringBootTest`
  asserting those beans exist.
