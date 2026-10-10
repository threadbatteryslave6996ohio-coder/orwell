# Open issues

Defects found reviewing the current working tree. Every one was verified by reading the code — none
is a guess. Fixed entries are struck through and kept, so the file records what was decided as well as
what is open. Duplication and refactor debt are **not** here; they go in
[`CODE_REFACTOR.md`](CODE_REFACTOR.md), which is this repo's backlog.

Ordered by what breaks worst.

**Checked and clean**, so a later pass need not repeat it: nothing built, downloaded or generated is
committed (no `node_modules`, `target/`, `.class`, `.jar` or `.db`), and no `.env` is tracked — only
`.example` files. No test is `@Disabled` or skipped. `db-init/all-services.sql` gives each app its
own role owning its own database, with no cross-grants and no `SUPERUSER`. No `printStackTrace`, no
`Files.lines`/`walk` left unclosed, and every empty catch is the deliberate `ignored` convention.
`dashboard/server.py` guards path traversal against its static root and binds loopback only.

## ~~1. `seed-secrets.sh` aborts halfway through a real run~~ — RESOLVED BY DELETING THE SCRIPT

`scripts/seed-secrets.sh` is gone. It aborted partway through on `$JARVIS_AUTH_KEY`, a variable
renamed out from under it; it could not be re-run after that (the admin API answers `409` for an
existing group, and `curl -sf` under `set -euo pipefail` stops the script); and 44 of the envs it
created reached no bundle, so five services' configuration was seeded where nothing loads from.

A fresh secrets manager is now populated through its own `/admin` API or UI. `EnvLoader`'s `url`
mode fetches a bundle by id and never cared how the bundle came to exist, so nothing that ran
before still needs the script. See `scripts/README.md`.

## 2. `ServiceHeaderHttpClient` is never called, so two documented features are inert

Nothing in the repo calls `ServiceHeaderHttpClient.wrap`. Every outbound client — checked, all of
them — wraps with `LoggingHttpClient` alone; the only references to the class are
`ProxyAuditRecord:134` reading `SERVICE_HEADER` and two comments.

Two things silently do not work:

- **`request_audit.service` is NULL for every request an orwell service makes.** So
  `request_audit_service_idx` serves nothing, and the "what did service X do" query that
  `apps/reverse-proxy/README.md` and `docker-compose.all-services.yml` both advertise returns
  nothing.
- **No service forwards `X-Request-Id` on its own outbound calls.** `ServiceHeaderHttpClient` is
  what would attach it, and `stamped()` is the only place an ordinary outbound client would read
  `RequestId.current()` — so the id `RequestIdFilter` binds to the serving thread dies there, and a
  caller and its callee mint different ids whose records cannot be joined. That is the single
  property the `request_id` column exists to provide.

  **One exception, and it is the only hop that works:** the reverse proxy sets the header itself in
  `UpstreamClient:93` (`setHeader`, deliberately, with a comment saying why it must *not* wrap in
  `ServiceHeaderHttpClient` — `Service` has to stay the original caller's). `ProxyRequest:81` is
  therefore a second reader of `RequestId.current()`. So proxied traffic correlates end to end;
  direct service-to-service calls do not.

`CLAUDE.md` used to state the convention ("Every outbound client in the repo is built this way") as
established fact; its "An outbound `HttpClient` says who it is" bullet now says the wrapper is
unadopted and points here, and the same correction went into `packages/http-client/README.md`, the
root README and `apps/reverse-proxy/README.md`. **The docs no longer claim this works — the code
still does not do it, so this entry stays open.** `CODE_REFACTOR.md` already carries the adoption
item and enumerates the ~15 call sites; this entry is here because the gap is a working feature that
silently does nothing, not just debt.

**Fix:** wrap at the call sites — `LoggingHttpClient.wrap(ServiceHeaderHttpClient.wrap(client,
"<app>"), logger)`, as that class's own javadoc shows. The reverse proxy's `UpstreamClient` stays
unwrapped on purpose (a relay must pass the original caller's name through).

## ~~12. One unresponsive webhook subscriber stopped gmail-general entirely~~ — FIXED

`WebhookSender` built `HttpClient.newHttpClient()` — **no connect timeout** — and posted with **no
`.timeout(...)`**, to a URL the subscriber chooses (`webhook_subscriptions.url`; registration is
authenticated, so this was cross-tenant rather than anonymous). Four things composed:

- `WebhookDeliveryJob.deliverPending()` walks every active subscription **sequentially**, with a
  per-subscription `catch` whose comment reads *"continuing with the rest."* That isolates a
  subscription which **fails** — but a hang is not an exception, so the catch never fired and the
  loop simply never advanced. The one property the code existed to provide was the one that broke.
- Nothing in the repo sizes the scheduler pool, so Spring's default is **one thread**, and
  gmail-general is the only service with two `@Scheduled` methods on it. The other is
  `ImapMailPoller.scheduledPoll`, so the same stall stopped **mail arriving at all**.
- The job's `delivering` flag stayed true forever, so every later round was skipped too.

Net effect: one subscriber pointed at a host that accepts connections and never answers halted
webhook delivery for every subscriber *and* mail ingestion, permanently, with no exception, no log
line and no metric moving.

**Fixed** with a 10s connect and 30s request timeout, so a dead subscriber costs one timeout per
round and is reported like any other failure.
`WebhookDeliveryJobTest.aSubscriberThatNeverRespondsDoesNotBlockTheOthers` holds the line: it
stands up a receiver that accepts and never answers, and asserts the round still completes and the
subscription *behind* it still gets its mail. Removing the `.timeout(...)` makes it fail by
blocking past 20 seconds — verified, not assumed.

## ~~13. log-analyzer's Grafana query was unbounded, and single-flight made a stall permanent~~ — FIXED

`LogAnalyzerService.fetchRecentErrorLogs` built its Loki query with no `.timeout(...)`; the client
carried only a connect timeout, which does not bound a Grafana that accepts the request and then
stops answering. Because `pollOnce()` is single-flighted, that did not merely delay one poll —
every later poll returned the busy envelope, so log triage stopped for good with `pollsTotal`
frozen on `/health`.

Its twin `LokiHeartbeatClient` has always set `.timeout(Duration.ofSeconds(10))`. `CODE_REFACTOR.md`
already names that gap as "the drift this shape produces"; what it did not say is that the drift
was load-bearing. **Fixed** by matching the twin. The AI leg was never affected — `OpenAiModel` sets
its own timeout.

## ~~14. The same Loki timestamp parse was guarded in one analyzer and not the other~~ — FIXED

`LogAnalyzerService` parsed Loki's nanosecond timestamps with a bare
`Long.parseLong(String.valueOf(value.get(0)))`. A null element (`String.valueOf` turns it into
`"null"`) or any unexpected shape threw `NumberFormatException` out of the fetch and aborted the
round — and kept aborting it, every interval, for as long as that line stayed inside the lookback
window. The identical parse in `LokiHeartbeatClient` was wrapped in `parseNanos`, which skips the
line: *"one bad line, not a reason to fail the whole check."*

**Fixed** by giving log-analyzer the same guard, now covered by `LogAnalyzerServiceTest` — the first
tests this parsing has ever had.

## ~~15. Outbound clients without timeouts, repo-wide~~ — FIXED

The same omission in four more places, found by sweeping every `HttpRequest.newBuilder` and
`HttpClient` construction:

- **`HttpAuthenticationStrategy`** — the most exposed of them. This client is what the shared 401
  guard calls, so `/tokens/check` runs on *every authenticated request to every service*. With
  neither a connect nor a read timeout, an auth server that accepted connections and stopped
  answering would not fail those requests — it would park each one on a servlet thread until the
  pool was gone, turning one sick service into every service being down. Now 10s connect, 15s read.
- **`PersonDetectionService`** (alert POST, on the frame listener — a stalled alerting service
  stopped frames being consumed) and **`AnalysisWorker`** (frame POST, stalling the drain loop):
  10s connect, 15s request.
- **Both analyzers' `AlertClient`s**: 15s request.
- **`ObjectStorageProxyClient`**: connect timeout only, deliberately — `upload`/`batchUpload` stream
  files whose transfer can legitimately outlast any read timeout worth setting.

Two clients are exempt and say so in place: `FrameStreamClient` (an SSE stream is *meant* to stay
open) and `ServiceHeaderHttpClient` (it copies a caller's request; imposing a timeout would
silently change the caller's own). `packages/http-client/README.md` claimed "every client here sets
a connect timeout" while three did not; the claim is now true, and states the connect/request
distinction that made it easy to get wrong.

## 3. `LoggingHttpClient` logs credentials embedded in a URL

`packages/http-client/.../LoggingHttpClient.java:198`. `withoutQuery` cuts the URI at the first `?`
or `#` because *"query strings carry credentials often enough that logging them is a liability"* —
but `http://user:password@host/path` has no `?`, so it is logged whole, and shipped to Loki with
everything else.

This is reachable from user input, not just operator config: `WebhookSender:41` wraps with
`LoggingHttpClient`, and the URL it posts to is a subscriber-registered
`webhook_subscriptions.url` (`WebhookSubscriptionEntity:57`, 2048 chars). A subscriber who
registers `https://user:pass@their-host/hook` has those credentials written on every delivery.
Operator config is exposed the same way — a Grafana base URL is a conventional place to put basic
auth, and `LivenessService` and `LogAnalyzerService` both build from config through this wrapper.

**Fix:** drop userinfo as well — rebuild from `getScheme()`/`getHost()`/`getPort()`/`getPath()`, or
cut at `@` within the authority.

## 4. `JdbcAuditStore`'s retention sweep blocks the writer and drops rows

`apps/reverse-proxy/.../JdbcAuditStore.java:257`. `sweep()` runs on the writer thread between
batches as a single unbounded `DELETE FROM request_audit WHERE received_at < ?`. Nothing drains the
queue while it runs, so on the first sweep after retention is lowered — or the first one against an
already-large table — the 10k queue fills and `submit()` starts dropping records.

It is also the "one enormous WAL burst and a vacuum storm" that this repo's own `TokenCleanupJob`
javadoc says to batch away; that job batches at 500 for exactly this reason.

**Fix:** batch the delete the way `TokenCleanupJob.sweep` does, or move it off the writer thread.

## 5. `CheckTokenHttpRequest.token` has no size cap

`apps/auth/http-based/api/.../CheckTokenHttpRequest.java`. `clientId` is capped at 128 and
`LoginHttpRequest.secret` at 256; `token` has `@NotBlank` and no `@Size`. It and one
admin-authenticated secrets-manager field are the only `@NotBlank` strings in the repo without a
cap.

`/tokens/check` is unauthenticated and the busiest endpoint in the stack — every authenticated
request to every service lands there — so an unauthenticated caller can make each request carry a
multi-megabyte "token" that the server SHA-256s and looks up. Bounded by the container's max body
size, so this is amplification rather than a hole, but a generated token is exactly 43 characters.

**Fix:** `@Size(max = 512)` or similar.

## 6. `CredentialHasher.matches` is only half-guarded

`apps/auth/http-based/server/.../CredentialHasher.java:31`. It defends against a wrong *shape*
(`parts.length != 4 || !"pbkdf2".equals(parts[0])` returns `false`) and then parses unguarded:
`Integer.parseInt(parts[1])` plus two Base64 decodes. A `secret_hash` that is corrupted or
hand-edited throws out of the `.filter(...)` in `login`, so the caller gets a 500 where every other
bad-credential path returns 401 — which also distinguishes "this identity exists with a broken row"
from "wrong password".

**Fix:** wrap the parse and return `false`, matching the shape check above it.

## ~~7. Wrong admin credentials reach the panel as a 401 about the operator's password~~ — FIXED

`AuthServerClient.createIdentity` now resolves the admin token *before* the call it guards, so a
failure of this service's own credentials is told apart from a failure of the request the operator
made. Both ways our credentials can be refused — the admin login itself, and the identities endpoint
still refusing a token minted seconds ago, which means that identity is not an admin — now answer
503 and audit `authserver.identity.create.admin_login_rejected`. A 409 for a `clientId` that already
exists still keeps its own status, because that one really is about what they typed. Covered by
`AuthServerClientTest`.

## 8. `JdbcAuditStore` reports rows as dropped that are in the table

`apps/reverse-proxy/.../JdbcAuditStore.java:65`. `BATCH = 100` is commented "Rows per transaction",
but Hikari defaults to `autoCommit = true` and `write()` never begins one, so every statement in the
batch commits independently. A `BatchUpdateException` partway through therefore leaves rows
committed while the catch in `run()` adds the whole `batch.size()` to `droppedTotal` and never
increments `writtenTotal`. `/health` then under-reports what was stored.

**Fix:** `setAutoCommit(false)` plus an explicit commit, or count from `executeBatch()`'s return and
correct the comment.

## 9. `ClientAuthSession` defers workspace validation to first use

`apps/auth/http-based/client/.../ClientAuthSession.java:55` calls `AuthWorkspaces.orDefault` with no
`isValid` check, while `HttpAuthenticationStrategy` deliberately validates at construction ("the name
cannot become valid later"). `new ClientAuthSession(url, "Team A", id, secret, null)` constructs
fine and throws `IllegalArgumentException` from inside `refresh()` at the first token acquisition —
an unchecked exception at call sites that handle only `HttpAuthenticationException` and
`IllegalStateException`.

**Fix:** validate in the constructor, as the strategy does.

## ~~10. Two tracked directories are outside the documented repo structure~~ — DOCUMENTED

Both are now written down: `CLAUDE.md`'s opening names `fitness-tracker/` and `dashboard/` as
tracked, non-Java, outside the Maven reactor (alongside the Android module, which was the existing
precedent for "`mvn` proves nothing about this"), and the root README's "Other directories" table
has a `fitness-tracker` row. Whether either *should* live in this repo is still open, and the
untracked `Dockerfile`/`.dockerignore` note at the bottom of this file still stands. The original
report follows.

`CLAUDE.md` opens "Multi-module Java 25 / Maven monorepo: backend services under `apps/`, shared
libraries under `packages/`" and its module map lists only those. Two tracked directories fit
neither:

- **`fitness-tracker/`** — 26 tracked files: a Next.js / TypeScript app with Drizzle migrations and
  its own `.gitignore`. Mentioned in no repo-level doc at all. Its `Dockerfile` and `.dockerignore`
  are also untracked, so the tracked half does not build as committed.
- **`dashboard/`** — a Python `http.server` proxy plus nginx config and static assets. One mention
  in the root README, none in `CLAUDE.md`.

Neither is wrong to exist, but an agent or a new contributor reading `CLAUDE.md` will not know they
are there, and `mvn` says nothing about either. ~~**Fix:** a row each in the root README table and a
line in `CLAUDE.md`~~ — done — or move them out of the repo.

## 11. Shell scripts mask failures inside `local`/`declare` — HALF FIXED

`shellcheck -S warning` is otherwise clean across every tracked script. One pattern left:

- ~~**SC2155**, in `jarvis/clients/linux/start_recorder.sh:212`, `jarvis/clients/mac/manage.sh`
  (four sites) and `jarvis/clients/mac/start_recorder.sh:23`. `local x="$(cmd)"` takes the exit
  status of `local`, not of `cmd`, so a failing command inside is invisible even under `set -e`.~~
  **Fixed.** All six sites are gone: the two recorders and the two `manage.sh` scripts were rewritten
  onto `clients/lib/recorder.sh` and `clients/lib/console.sh`, and the remaining declarations are
  split from their assignments. `shellcheck -x` is clean on all four files. See the jarvis recorder
  entry at the top of [`CODE_REFACTOR.md`](CODE_REFACTOR.md).
- **SC2010**, in `object-storage-proxy/scripts/local-stack.sh:121` and `:125` — `ls | grep` breaks
  on filenames that are not plain alphanumerics. A glob or `find` is the fix. Still open (verified
  against the current tree).

## ~~16. `pg_dump`'s output could deadlock the backup forever~~ — FIXED

`BackupRunner.dump()` set `redirectErrorStream(true)`, then called `process.waitFor()` and read the
stream **only afterwards**. Nothing drained the pipe while pg_dump wrote to it, so once the output
passed the OS pipe buffer (~64 KiB) the child blocked writing and the parent blocked waiting —
forever, with no timeout and no log line. Reproduced directly: 1 KB of child output completes, 200 KB
never returns from `waitFor()`.

It hid because the dump itself goes to `-f`, not stdout, so ordinary runs emit almost nothing. It
would surface on the run that produced a lot of diagnostics, which is precisely the run you need to
see finish.

**Fixed** by redirecting the child's output to a temp file (a file write never blocks, so nothing can
wedge) and bounding the wait with `waitFor(60, MINUTES)` plus `destroyForcibly()`. Only the tail of
the output reaches the exception message. Note `jarvis-frame-viewer`'s `VideoFrames` has the
**opposite** half of this same idiom — it drains on the calling thread *before* `waitFor(timeout)`,
so its timeout can never fire; that one is still open.

## ~~17. `BACKUP_RETENTION_DAYS` silently did nothing for a project with its own storage directory~~ — FIXED

`resolveStorage()` sends a project to `BACKUP_<NAME>_STORAGE_DIR` when set, but `cleanUp()` walked
`BACKUP_OUTPUT_DIR` alone. Dumps in a per-project directory were never swept — while the run still
logged "Cleaning up expired backups", so the setting was configured, the log said it ran, and the
disk filled anyway. Invisible by construction: nothing reports retention it did not perform.

**Fixed** — `cleanUp`/`cleanUpEmptyDirs` now take the project list and sweep every directory a dump
can land in.

**The first attempt at this fix was itself a serious bug, caught in review before it shipped.** It
widened the sweep to the operator's `BACKUP_<NAME>_STORAGE_DIR` while keeping the old body: a
recursive `Files.walk` deleting *every* regular file older than the cutoff. The documented use for
that setting is a separate mount such as a NAS, so the first run would have deleted the operator's
unrelated data — trading a disk that fills up for data loss. Widening where a delete reaches without
narrowing what it deletes is the whole lesson here.

The sweep is now scoped three ways: only files ending `.dump`, only directly inside the per-project
directory `DirectoryStorage` creates, and never recursive. `cleanUpEmptyDirs` likewise considers only
that one directory rather than walking for empty ones. `DirectoryStorage.projectDirectoryName` is
public so the writer and the sweep cannot disagree about where dumps live.
`BackupRunnerRetentionTest` covers both halves — that expired dumps go, and that an operator's files
beside and below the project directory, a non-`.dump` file inside it, and an unrelated empty
directory all stay.

## ~~18. Storing a dump to another filesystem failed after the dump had been paid for~~ — FIXED

`DirectoryStorage.store()` used `StandardCopyOption.ATOMIC_MOVE` to move the dump from
`BACKUP_OUTPUT_DIR` into the project's storage directory. An atomic move cannot cross a filesystem
boundary, and a separate mount — a NAS, a bigger disk — is the usual reason to configure a separate
directory at all. `AtomicMoveNotSupportedException` then ended the run *after* pg_dump had already
run.

**Fixed** by catching it and falling back to a copy-and-delete move. That is no longer atomic, which
costs a partial file if the process dies mid-copy; a partial file that the next run replaces is
cheaper than never storing one.

## ~~19. The liveness dead-man's switch had no fuse against its own source failing~~ — FIXED

`LivenessService.runCheck` treated `seen == null` as down, and `latestHeartbeats()` returning nothing
is indistinguishable from every client dying in the same second. Loki restarted, Loki retention
shorter than `LIVENESS_LOOKBACK_SECONDS`, a changed label, or the analyzer pointed at a different
Loki than clients push to — any of them fires one alert per client at once. The per-client cooldowns
bound repeats, not the initial burst.

The repo had already solved this shape and knows it is dangerous: insta's `FollowWriter` refuses a
walk that would retire more than `INSTA_MAX_RETIRE_PERCENT` of an account's edges, on the reasoning
that "a private account returns an empty list, and that is indistinguishable from everyone leaving at
once."

**Fixed** with `LIVENESS_MAX_DOWN_PERCENT` (default `100`, `0` disables, never applies below three
clients). A check at or above it dispatches nothing, reports `alertsSuppressed` and the reason on
`/health` and in the run response, and still records every client as down — the fan-out is held, not
the information. The default withholds only on a total wipeout, so partial outages alert exactly as
before. `runCheck` now decides first and alerts second, because alerting inside the loop had already
sent half of them by the time the total was known.

## ~~20. `dashboard/server.py` answered nothing at all on POST/PUT/DELETE to an unknown path~~ — FIXED

`_proxy()` returns `False` when no backend prefix matched. `do_GET` fell through to `_static()`, but
`do_POST`, `do_PUT` and `do_DELETE` discarded the return and wrote no response — the handler returned
and the connection closed. Verified against the real handler: the client saw `RemoteDisconnected`,
not a status. **Fixed** — all three now `send_error(404)`.

Its static traversal guard was also `os.path.abspath(fpath).startswith(os.path.abspath(STATIC_DIR))`,
which accepts a sibling directory whose name merely begins with the root's (`/x/static-evil` passes a
`/x/static` root). No such sibling exists, which is exactly why the wrong shape would have survived
until one did. **Fixed** with `os.path.commonpath`. The "checked and clean" note at the top of this
file called this guard correct; it was correct in effect, not in shape.

## ~~21. `fitness-tracker` served the wrong end of the file for a suffix byte-range~~ — FIXED

`src/pages/api/video/[name].ts` parsed ranges with `/^bytes=(\d*)-(\d*)$/` and read the empty first
group as a start of 0. So `bytes=-500` — the **last** 500 bytes per RFC 9110 §14.1.2 — returned the
**first** 501, under a 206 and a `Content-Range` claiming it was what was asked for. Nothing errored;
a player hunting the `moov` atom at the end of a non-faststart MP4 got header bytes and failed to
start. **Fixed** with a suffix branch, verified across eight range forms including `bytes=-0`
(unsatisfiable → 416) and a suffix longer than the file (clamped to the whole file).

## ~~22. `fitness-tracker` never delivered its 413 for an oversized upload~~ — FIXED

`src/pages/api/upload.ts` enforced its 200 MB cap with `req.destroy(new Error(...))` inside the
`data` listener, then answered `413` with a JSON message from the `catch`. Destroying an
`IncomingMessage` tears down the socket, so the response could never be written: the uploader saw
`ECONNRESET` — indistinguishable from a flaky network — and `res.end()` threw nothing, so the handler
believed it had answered and no trace of the real outcome existed anywhere.

**Fixed** by never destroying the request. An oversized `Content-Length` is refused before a byte is
read or a file created; a body that exceeds the cap mid-stream stops being written to disk and is
drained to nothing, then answered `413`. Verified end to end: under-cap → 201, oversized declared and
oversized chunked → 413 with the message.

**A second defect was introduced replacing `pipeline()` with a hand-rolled loop, and caught in
review.** `pipeline` attaches an `'error'` listener to every stream it manages; the loop did not, and
`once(sink, "drain")` attaches one only while actually waiting for drain — the *uncommon* path. So on
the common path, where `write()` returns true, a disk-full or `EACCES` error had no listener at all,
and Node turned it into an uncaught exception that kills the Next process instead of a 500.
Reproduced with a sink whose `write()` returns true and fails asynchronously: without a persistent
listener the process exits 9; with one the request answers `500 {"error":"ENOSPC"}`. **Fixed** with a
persistent `'error'` handler recording the first failure, checked each iteration and before `end()`.

## Not a defect, but probably unintended

`fitness-tracker/Dockerfile` and `fitness-tracker/.dockerignore` are untracked and unrelated to
anything else in the working tree. `fitness-tracker/` is a separate Next.js project living in the
repo root, with its own `node_modules` and a committed `fitness.db`. Worth deciding before the next
commit sweeps those two files in.
