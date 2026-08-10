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

Also done since (this pass): the `spring-boot-maven-plugin` repackage+`exec` block now lives once in
the **root** `pluginManagement` — each app carries only a `<start-class>` property and a bare plugin
ref (§1); `apps/auth/pom.xml` and `apps/secrets-manager/pom.xml` are re-parented to the root and no
longer re-declare the Spring BOM or compiler/surefire management — `-parameters` was enabled on the
root compiler plugin so secrets-manager keeps constructor-name binding (§1); the redundant `env-http`
dependency was dropped from the six poms that pull it transitively via `server-bootstrap`; four dead
files are gone — `Flag.java`, `packages/env/http/.../HttpExchangeResponses.java`, secrets
`admin/GroupDetailResponse.java`, and secrets-client `PasswordAuthProvider.java`; `ClientAuthSession`
(token cache + login + 401 refresh) was promoted from klippy into `auth-http-client` and `GmailService`
now reuses it instead of its own inlined login cache (the §2 "Gmail webhook auth" item); and two
in-file dedups landed — `BucketProxyClient`'s six copy-pasted try/catch blocks collapsed onto a shared
`call(...)`/`rejected(...)` pair, and `SecretsManagerClient`'s two `execute` overloads merged into one
deserializer-parameterized method.

Also done since: secrets-manager's 23 hand-repeated `requireAdmin()` / `requireAccessor()` calls —
one per handler, fail-open if you forgot one — are three `@RequireAdmin`/`@RequireAccessor` class
annotations enforced by `SecretsRoleInterceptor`, with `SecretsRoleCoverageTest` failing the build if
any handler under `dev.orwell.secrets.controller` declares no role (the annotation alone is not
fail-closed: an unannotated handler is still served). `AbstractSecretsAdminController` is gone with
them, and the two handlers that stamp `createdBy` take the caller as a `@RequestAttribute` the guard
already resolved rather than re-validating. Note the guard runs ahead of body parsing, so an
unauthenticated request with an invalid body answers 401 rather than 400 — the same ordering
`@RequireAuthentication` documents.

Also done since (auth dedup): `HttpAuthenticationStrategy` kept two copies of the same RestClient
error plumbing, one per endpoint — they share one `post(...)` now, so the two calls cannot drift into
reporting the same failure differently. `ClipboardApiClient`'s `currentToken()` and
`refreshFailure()` encoded one classification rule twice, with javadoc reading "mirrors the other" —
a keep-in-sync-by-hand hazard — and are one `authFailure(...)`; its stale claim that a blank token
from the auth server arrives as `IllegalArgumentException` (it became an
`HttpAuthenticationException` when `ClientAuthSession.tokenFrom` was fixed) now names the cause that
can still produce one, a malformed base URL. And `/tokens/check` emits one log record instead of two
on what is the highest-volume path in the repo: every authenticated request to every service lands
there.

Also done since (auth pass): the auth server's `logback-spring.xml` — the last one in the repo, and a
non-rolling `FileAppender` that contradicted "servers no longer write an app log file by default" — is
deleted, so `LOGGING_FILE_NAME` went optional there (`new AppServerEnv(false, false)`) and dropped out
of the compose stack and `.auth-server-env.example`. Three pieces of dead code went with it:
`ClientTokenRepository.findByTokenHash` (unused, and the non-`join fetch` variant, so a trap),
`InMemoryAuthenticationStrategy.authenticate` (byte-identical to the interface default), and the
`identityId` that `AuthController` put on the wire and `HttpAuthenticationStrategy` parked in
`AuthenticationContext` where nothing ever read it — removed from the record, from
`CheckTokenHttpResponse`, and from the four controller tests that passed a fixture value for it. The
server README's stale claims went too: an `auth-server.txt` `CustomLogger` audit log that no code
creates, and an Azure datasource note left over from the deleted devops stack.

Also done since: the two hand-rolled Redis wrappers are one shared module. `dev.orwell.redis.RedisClient`
(`packages/redis-client`) owns the `JedisPool`, the connect timeout, the key prefix its constructor is
given, and the translation of a driver failure into an unchecked `RedisOperationException`;
keeboarder's `RedisClientCache` and insta's `RedisScrapeCache` keep only their data shape and their
(deliberately different) failure policies. Jedis is no longer pinned per app — keeboarder's local
`4.4.3` management is gone, so the whole repo takes the version from the Spring Boot BOM.

## 0. Operational notes from the naming pass

Not backlog items, but worth knowing:

- **The Android module is not in the Maven reactor.** It builds with Gradle, so `mvn test` proves
  nothing about it. Changes there are unverified by CI here and want a real Gradle build.
- **DEPLOY ACTION — servers no longer write an app log file at all.** The Spring default sink is
  console + Loki push. Any external log rotation, volume mount, or shipping config keyed to
  `<app>.txt` or `<app>.jsonl` now watches a file nobody writes. `CustomLogger` still writes
  `.txt` where it is used directly (`EnvSnapshotLogger`, `PollInterval`).

## 1. `spring-boot-maven-plugin` block → root pluginManagement — DONE

Done — see the note at the top. The repackage+`exec` block moved to the **root** `pluginManagement`
(not `server-parent`, since only three of the ten executable apps use `server-parent`); each app now
carries a `<start-class>` property plus a bare plugin ref. `apps/auth` and `apps/secrets-manager` are
re-parented to the root.

## 2. Smaller cleanups

- **secrets-manager deletes ignore their own foreign keys**: `SecretsService.deleteGroup`,
  `deleteEnvironment`, and `deleteBundle` each call `repo.delete(entity)` with no cascade in the
  entities and no `ON DELETE` action in the generated DDL, so deleting a group that still has
  environments (or an env still referenced by a bundle entry) hits the FK and surfaces as a 500.
  `server/README.md` claims a group delete cascades; it does not. `setBundleEnvironmentReferences`
  is the only path that clears dependents first. No test covers any of the three. Also note
  `secret_bundle_entries(env_id)` has no index, so the FK check scans.
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
- **Gmail webhook auth — DONE (partial).** `ClientAuthSession` (token cache + login + 401 refresh) was
  promoted from klippy's client-core into `auth-http-client` (`dev.orwell.auth.http.client`), and
  `GmailService` now reuses it instead of hand-rolling a `LoginHttpResponse` cache. The remaining two
  "variants" turned out **not** to share this shape: `SecretsManagerClient` uses a static bearer token
  (`TokenAuthProvider`) with no refresh, and `BucketProxyClient` takes the token per call from the
  caller. The password-login-with-cache class (`PasswordAuthProvider`) was dead and is deleted, so
  there is no live 401-refresh path left to unify there.
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
  - *`POST /identities` is unauthenticated and publicly reachable* — **the highest-value item, and
    unlike the rest of this list it is a live authorization gap.** `AuthController` carries no
    `@RequireAuthentication`, and `nginx/all-services.conf` proxies `/auth/` off the published port,
    so anyone who can reach the stack can self-register an identity and log in for a token that every
    client-side service accepts. `admin-auth-server` has no nginx location, so admin is not reachable
    this way — which is what makes the client server's open registration look like an oversight.
  - *Tokens never expire and cannot be revoked*: `ClientToken` has no `expiresAt` and `checkToken`
    never checks age, so a leaked token is valid until someone deletes the row by hand. Every login
    also inserts a row that nothing ever removes.
  - *Timing enumeration on unknown clientId*: an empty `findByClientId` short-circuits the filter
    chain before PBKDF2 runs, so an unknown clientId answers ~100ms faster than a known one. Bigger
    signal than the inactive-identity channel above it, on the same unthrottled public endpoint.
  - *`ClientAuthSession` refresh is heavier than it needs to be*: `refresh()` builds a new
    `HttpAuthenticationStrategy` (and `RestClient`, and `ObjectMapper`) per call, and because
    `refreshIfUnauthorized` is `synchronized`, N threads that all see a 401 serialize into N logins —
    N full PBKDF2 runs on the server that has no rate limit.
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
  (linux/mac/dummy/offline-sync/file-locker). Deferred deliberately: the shade *configuration* already
  lives once in the root `pluginManagement`; what repeats is only the 4-line activation stanza, and the
  four shaded clients share no parent that *only* they use — `apps/klippy/pom.xml` also parents the
  server, utils, and client-core, which must **not** be shaded. Collapsing this cleanly needs a new
  intermediate "clients" parent; not worth the structural churn for ~16 lines.
- **Dead code**: DONE — `Flag.java` deleted, along with three other verified-orphan files found in the
  same pass (`packages/env/http/.../HttpExchangeResponses.java`, secrets `admin/GroupDetailResponse.java`,
  secrets-client `PasswordAuthProvider.java`).
- **Unguarded auto-config registry**: nothing tests that the four entries in server-bootstrap's
  `META-INF/spring/...AutoConfiguration.imports` resolve; a typo silently drops the shared
  `/health` endpoint, 401 guard, logger, and auth-strategy beans. Add a `@SpringBootTest`
  asserting those beans exist.

## 4. gmail-general: multiple mailboxes, and subscribers that pick accounts — IN PROGRESS

**Status.** §4.1 (schema), §4.2 (DB-managed accounts), §4.3 (concurrent per-user polling), §4.4
(the `account` field on the webhook payload), §4.5 (per-mailbox subscriptions with cursor-tracked
delivery) and §4.7 (docs) have landed.

**The one significant thing left is credential storage** — see §4.2: per-user IMAP app passwords
are still a plaintext `secrets.imap_password` column, so anyone with read access to the database, a
backup, or a replica holds every registered mailbox's live credentials. Note
`secrets-manager-client` is **read-only** (`listGroups`/`listEnvironments`/`getBundle` — no write),
so the reference approach needs each password provisioned in secrets-manager out of band first, and
changes `PUT /users/{id}/secret` from taking a password to taking a reference. Encrypting the column
with a key from the environment is the smaller alternative. Deliberately deferred, not overlooked.

The original problem: `gmail-general` was single-mailbox end to end — one set of `IMAP_*`
credentials as flat env scalars, one `@Scheduled` poll, and one static comma-separated fan-out list
every webhook client shared. Wanted: N mailboxes in one service, and subscribers that register for
specific mailboxes rather than receiving everything. Running one service instance per account is
**not** a shortcut around this — the instances would share the `gmail` database and collide on the
two constraints below.

**4.1 Schema blockers — these break silently, and must land first.** Both are wrong for more than
one account regardless of which config approach §4.2 picks:

- `imap_checkpoints` is keyed by folder alone (`ImapCheckpointEntity:19`, `@Id` on `folder`). Two
  accounts polling `INBOX` share one row and overwrite each other's UID cursor, so each skips the
  mail the other advanced past. Needs a composite key on `(account, folder)`.
- `email_messages.message_id` is globally unique (`EmailMessageEntity:33`). One email addressed to
  two subscribed accounts carries a single `Message-ID`, so the second account's copy is swallowed
  by the `existsByMessageId` check in `GmailService:60` — no error, just missing mail. Needs an
  `account` column and a unique constraint on `(account, message_id)`.

There is **no Flyway or Liquibase in this repo** (checked: nothing in any pom) and
`spring.jpa.hibernate.ddl-auto=update` will not alter an existing primary key or drop a unique
constraint. So this needs hand-written SQL, run before the new jar starts: add `account`, backfill
existing rows with the current `IMAP_USERNAME` value, drop the old unique index and the old PK,
add the composite ones. `db-init/all-services.sql` creates the role/database only and is not a
migration home; decide where that SQL lives as part of this item.

**4.2 Where the account list comes from — decision still open.** Two viable shapes:

- *Env-driven*: one `IMAP_ACCOUNTS` value parsed into typed account records by a custom
  `EnvType.of(...)` parser (the framework already supports custom parsers). Smallest change, app
  passwords stay in env as today, adding a mailbox is an env edit plus a restart.
- *DB-managed*: an `imap_accounts` table plus authenticated `POST`/`DELETE /accounts`. Runtime
  registration, and it composes with §4.5 since both accounts and subscribers become live state.
  Costs credential storage — per-account app passwords should not be a plaintext column, and
  `secrets-manager-client` already exists for exactly this, so the row would hold a secret
  reference. The poller also has to notice accounts added or removed between ticks.

Per-account subscriptions (§4.5) work with either: a subscription row references an account by
name whether that name came from env or from a table.

**4.3 Poller fan-out — DONE.** `ImapMailPoller` dispatches one poll per user onto a bounded pool
(`GMAIL_POLL_CONCURRENCY`, default 4), each user's body wrapped in its own try/catch, and the single
shared `polling` flag replaced by one `AtomicBoolean` per user id. A mailbox whose previous poll is
still running is skipped for that round alone; every other mailbox proceeds. Flags for deleted users
are dropped each round so the map cannot grow for the process's lifetime, and the pool threads are
daemons so a hung IMAP read cannot keep the JVM alive past context close.

Still open (minor): the poll interval is still global — a per-account interval remains
unimplemented and is still worth considering if mailboxes differ a lot in traffic.

**4.4 DTO and read API — DONE (webhook side).** `GmailMessage` now carries `account`, sourced from
the polled user's row rather than a header (a mail can arrive by Bcc, alias, or forwarding, so `to`
is not the owner). `apps/analyzer` only deserializes it as a `@RequestBody` and was unaffected, as
predicted.

Still open: `MailResponse` has no `account` field, and `/mails`/`/mails/latest` have no `account=`
filter. Both are lower value than they were — a reader is already scoped to exactly one mailbox by
its client id, so there is nothing to disambiguate — and they only become necessary if one consumer
is ever allowed to own several mailboxes.

**4.5 Per-account subscribers — DONE.** `webhook_subscriptions` (user, url, active, created) plus
`@RequireAuthentication`-guarded `GET`/`POST`/`DELETE /subscriptions` in `SubscriptionController`.
The mailbox comes from the injected `AuthenticationContext`, never the request body, so a consumer
can only subscribe its own mailbox and can only list or delete its own rows; a delete addressed to
another user's id is 404, not 403, so ids cannot be probed. `GmailService.targetsFor(user)` now
resolves each message's targets from that user's active subscriptions. No separate `client_id`
column: the owning user already carries the consumer's client id, and a second identity here could
disagree with it.

`GMAIL_WEBHOOK_CLIENTS` is retained as an explicit "subscribed to all accounts" broadcast for the
transition, now logged as a WARN at startup and documented as legacy. Targets are deduplicated, so
a URL that is both broadcast-configured and subscribed is delivered once — migration can be
gradual. **Deleting it is the remaining step**, and until then it is the one path that still hands
every mailbox's mail to one receiver.

**Delivery durability — DONE.** Each subscription carries `last_delivered_id`, and
`WebhookDeliveryJob` (`GMAIL_DELIVERY_INTERVAL_SECONDS`, default 5) walks `email_messages` forward
per subscription, advancing the cursor only on a 2xx. A subscriber that was down catches up instead
of losing mail — previously, dedup on `(user_id, message_id)` meant a failed delivery was never
re-sent. Delivery is at-least-once and ordered per subscription: it stops at the first failure for
that subscription so later mail cannot overtake a rejected message, and other subscriptions continue
in the same round. A new subscription's cursor starts at the mailbox head, so subscribing does not
replay history. The HTTP/auth half is shared with the broadcast path via `WebhookSender`.

Still open: nothing ever sets `active = false`. A receiver that fails every round is retried
forever at the head of its own queue, which blocks that subscription's later mail indefinitely.
Wanted: a failure count on the row, and a threshold past which the job deactivates the subscription
and says so — the `active` column exists for exactly this.

**4.6 Tests.** Covered: `ImapMailPollerIntegrationTest` runs two GreenMail accounts with
independent checkpoints and per-user read scoping; `SubscriptionApiIntegrationTest` covers the
subscription API's scoping (cross-user list and delete both refused) and the head-start cursor;
`GmailServiceWebhookTest` covers the legacy broadcast and its skip of already-subscribed URLs;
`WebhookDeliveryJobTest` covers cursor advance, retry-after-failure, stop-at-first-failure ordering,
and per-subscription isolation.

Still open: no test asserts the same `Message-ID` is stored once *per account* rather than once
globally — the §4.1 constraint change is currently only proved by the migration, not by a test.

**4.7 Docs — DONE.** `apps/google/gmail-general/README.md` documents multiple mailboxes, the
`/users` and `/subscriptions` routes, the `webhook_subscriptions` table, the delivery payload
including `account`, and `GMAIL_WEBHOOK_CLIENTS` as legacy broadcast. `.env.example` carries the
same warning, and `migrations/001-multi-user.sql` has an optional step 6 for moving broadcast
receivers onto per-mailbox subscriptions.
