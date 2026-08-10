# insta

A command-line program that looks up **public** Instagram accounts: how many followers and
following an account has, and who those accounts are. It holds no Instagram credentials — each
miss runs an actor on the [Apify](https://apify.com) marketplace — and caches every answer in
Redis for a day, because those runs are billed per result.

There is no server, no port, and no HTTP API. It runs, prints, and exits.

```bash
mvn -pl apps/insta -am package
java -jar apps/insta/target/insta-0.1.0-SNAPSHOT-exec.jar profile nasa
```

## Usage

```
insta profile   <username>
insta followers <username> [--limit N] [--all] [--cursor C] [--json]
insta following <username> [--limit N] [--all] [--cursor C] [--json]
insta sync      <username> [--limit N]
```

| Option | Meaning |
|---|---|
| `--limit N` | accounts per lookup (default `INSTA_DEFAULT_LIMIT`, capped at `INSTA_MAX_LIMIT`) |
| `--all` | keep fetching pages until the list is exhausted |
| `--cursor C` | resume from a previous run's cursor |
| `--json` | print raw JSON instead of a readable list |

The username may be given with or without a leading `@` and is case-insensitive.

```console
$ insta profile nasa
username     nasa
name         NASA
followers    97,000,000
following    78
posts        3,900
private      false
verified     true

$ insta followers nasa --limit 500
alice	Alice
bob	Bob
carol
3 followers of nasa
```

Results go to **stdout** and nothing else does, so this composes:

```bash
insta followers nasa --all > followers.txt          # progress and counts stay on stderr
insta followers nasa --json | jq -r '.accounts[].username'
```

### Long lists

A single lookup returns at most 500 accounts (`INSTA_MAX_LIMIT`); a larger `--limit` is clamped
rather than rejected. `--all` walks the rest for you, printing progress to stderr as it goes —
each page is a separate paid actor run.

Without `--all`, a lookup that stopped early tells you how to resume:

```console
$ insta followers nasa --limit 500
…
500 followers of nasa
More remain. Resume with --cursor eyJ1IjoibmFzYSIsInQiOiJGT0xMT1dFUlMi…
```

The cursor carries the account and direction it was issued for, so replaying a followers cursor
against a `following` walk, or against another username, is refused rather than producing an
undefined result from the actor. Cursors expire upstream; an expired one fails the lookup, and the
fix is to start again without one.

### Exit codes

| Code | Meaning |
|---|---|
| `0` | success |
| `1` | unexpected failure |
| `2` | bad usage, an impossible username, or an unreadable cursor |
| `3` | no such public account (`profile` only) |
| `4` | Apify could not answer — bad token, failing actor, expired cursor, Instagram change |
| `5` | the Apify account is out of usage credit |
| `6` | the actor run outlived `APIFY_RUN_TIMEOUT_SECONDS`; retry with a smaller `--limit` |

`5` is deliberately not `4`: an exhausted balance means nothing is broken and someone has to top
the account up. A nightly job can alert on the bill without alerting on every failed scrape.

An **empty** follower/following list is a success, not an error: from here a private account and an
account with no followers look identical. Run `profile` and read `private` to tell them apart.

## The follow graph — `insta sync`

`sync` walks an account's followers and records them in Postgres, so you can ask what changed
since last time.

```console
$ insta sync nasa
nasa: 500 followers seen, 3 new, 1 unfollowed
```

Five tables, created automatically on first run (`CREATE … IF NOT EXISTS`, so there is no
migration tool to keep in step):

| Table | Holds |
|---|---|
| `account` | one row per account ever seen, keyed by **Instagram's own id** |
| `account_username` | every handle an account has used, with the window it was seen in |
| `account_bio` | every distinct bio, keyed by a digest so an oversized one cannot break the index |
| `account_profile_picture` | every distinct image, keyed by the **hash of the bytes** |
| `follow_edge` | who follows whom, with `first_seen_at`, `last_seen_at`, `lost_at` |

Identity is Instagram's id rather than a handle because handles change — and it is `TEXT`, not
`INT`, since real ids already exceed `INT` (`4014759590`).

### How an unfollow is detected

**Nobody ever observes an unfollow.** Instagram does not report one; you infer it from an account
being absent from a walk that saw *everything*. So `follow_edge.last_seen_at` is a watermark: a
complete walk refreshes everyone it sees, then retires whoever it didn't.

That inference is only safe when the walk is trustworthy, and three things make it not:

- **It stopped early.** Lookups cap at 500 per page and a walk can also end on a timeout, an
  expired cursor or an exhausted Apify balance. 500 of 40,000 followers would otherwise retire
  39,500. `sync` therefore always walks the whole list — `--all` and `--cursor` are rejected.
- **Rows had no id.** A row that can't be keyed can't be stored, and an unstorable row looks
  exactly like an absent one. Any of them blocks retirement for that walk.
- **It would retire implausibly much.** A private or deleted account returns an empty list, a
  perfect impression of everyone leaving at once. Above `INSTA_MAX_RETIRE_PERCENT` the walk refuses
  and says so. (`sync` also checks `private` on the profile first and skips the walk entirely.)

When retirement is skipped, the command says why rather than printing "0 unfollowed" — a silently
suppressed diff must not look like good news.

The whole write is one transaction: a sync that dies halfway leaves the graph as it was, because a
half-refreshed graph looks like unfollows to the next run.

### Profile pictures

Off by default (`INSTA_PICTURE_STORE=none`) — and with it off, no image is even downloaded.
Turn it on with `filesystem` (`INSTA_PICTURE_DIR`) or `http` (`INSTA_BUCKET_URL`, optional
`INSTA_BUCKET_TOKEN`), which PUTs to an S3-compatible endpoint or this repo's
`jarvis-bucket-proxy`.

Images are keyed by the **SHA-256 of their bytes**, never by URL. Instagram signs its CDN links, so
the same picture arrives at a different address on every scrape — keyed on the URL you would record
a "new picture" daily and re-upload identical bytes forever. Hashing also means the default avatar,
which a large share of accounts share, is one object rather than thousands.

A picture that can't be fetched or stored is logged and skipped. An expired CDN link is not a
reason to lose the follow data collected in the same sync.

### What it costs, and what isn't built yet

Each walk is billed per follower returned (from $0.60/1,000), so a daily sync of a 500-follower
account is roughly $9/month. Crawling *followers of followers* is a different order of magnitude —
about $150 per full pass at depth 2 and $75,000 at depth 3 — so there is deliberately no recursive
crawl here.

Not built yet: the alert dispatcher (`unfollow_notified_at` is written and indexed for it, but
nothing sends mail), and any scheduler — run `sync` from cron.

## Caching

Every answer is cached in Redis under a key covering everything that changes it — username,
direction, page size, cursor — so re-running a lookup, or re-walking a list, costs nothing for 24
hours.

- **A cache failure is never a lookup failure.** If Redis is down, a miss is the answer and you pay
  for a scrape. A cache outage should cost money, not availability.
- **Failures are not cached.** A missing account, a timeout, or a broken run leaves nothing behind,
  so a new or briefly unreachable account does not stay missing for a day.
- Set `INSTA_CACHE_ENABLED=false` to bypass it entirely; the program runs fine with no Redis at
  all, it just pays Apify every time.

### Sharing the one Redis

There is exactly one Redis in this repo (the `redis` service in `docker-compose.all-services.yml`)
and keeboarder-server is already in it under the `ws:` prefix. This cache namespaces every key
with **`insta:`**. The namespacing is not this program's to get right: both go through
`dev.orwell.redis.RedisClient` (`packages/redis-client`), which puts the prefix its constructor is
given in front of every key it touches.

Redis's own answer to "workspaces" is numbered logical databases (`SELECT n`), and they are the
wrong tool here: they share one memory pool, one persistence file and one eviction policy,
`FLUSHALL` still wipes every one of them, and Redis Cluster supports only database 0 — so picking a
database would quietly foreclose ever clustering.

The shared Redis runs with `--appendonly yes --appendfsync everysec`, so a restart loses at most a
second of cached results rather than up to an hour of them.

## Running without an Apify key

A real lookup needs a token — without one the program exits `2` before spending anything:

```console
$ insta profile nasa
Configuration error: Missing required environment variable: APIFY_TOKEN
```

What still works with no key, no Redis and no network: `insta --help`, every usage error, and the
whole test suite (`mvn -pl apps/insta -am test`).

To exercise the program itself, run the bundled fake Apify:

```bash
python3 apps/insta/scripts/fake-apify.py --pages 3 &
export APIFY_TOKEN=anything APIFY_BASE_URL=http://127.0.0.1:9401
java -jar apps/insta/target/insta-0.1.0-SNAPSHOT-exec.jar followers nasa --all
```

It serves canned data on both run paths and ignores the token. `--pages N` hands out continuation
tokens so `--all` has something to walk. It is a development fixture, not the test double — the
tests have their own stub and do not use it.

Redis is optional too: with none running, every lookup logs a cache warning and answers anyway.

### Getting a real key

Apify's free plan needs no credit card and gives $5 of credit a month. Sign up, then
**Settings → Integrations → API token** in the console, and put it in `apps/insta/.env`:

```
APIFY_TOKEN=apify_api_…
```

That file is gitignored (`.env` is, `.env.example` is not).

## Configuration

Settings come from the environment, or a `.env` file found upwards from the working directory —
real environment variables win. See [`./.env.example`](./.env.example). `APIFY_TOKEN` is the only
required one, and a missing one fails before anything can be spent.

| Variable | Default | Purpose |
|---|---|---|
| `APIFY_TOKEN` | — | Apify API token, sent as a bearer header (never in the URL) |
| `APIFY_BASE_URL` | `https://api.apify.com` | Apify API root |
| `APIFY_PROFILE_ACTOR` | `apify/instagram-profile-scraper` | actor behind `profile` |
| `APIFY_CONNECTIONS_ACTOR` | `scraping_solutions/instagram-scraper-followers-following-no-cookies` | actor behind `followers` / `following` |
| `APIFY_RUN_TIMEOUT_SECONDS` | `120` | per-run budget; a run that outlives it is aborted |
| `INSTA_DEFAULT_LIMIT` | `100` | accounts returned when `--limit` is omitted |
| `INSTA_MAX_LIMIT` | `500` | ceiling on `--limit` — a spend guard |
| `INSTA_CACHE_ENABLED` | `true` | whether to consult Redis at all |
| `INSTA_CACHE_TTL_HOURS` | `24` | how long a cached answer stays good |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` | the shared Redis |
| `INSTA_DATABASE_URL` / `_USERNAME` / `_PASSWORD` | *(empty)* | Postgres for `sync` |
| `INSTA_PICTURE_STORE` | `none` | `none`, `filesystem` or `http` |
| `INSTA_PICTURE_DIR` | *(empty)* | directory for `filesystem` |
| `INSTA_BUCKET_URL` / `INSTA_BUCKET_TOKEN` | *(empty)* | bucket endpoint for `http` |
| `INSTA_MAX_RETIRE_PERCENT` | `20` | fuse on how much one walk may retire |
| `INSTA_LOG_CONSOLE` | `true` | whether log records go to the console (on stderr) |
| `LOKI_URL` / `LOKI_TENANT_ID` | *(empty)* | ship records to Loki as well |
| `LOGGING_FILE_NAME` | *(empty)* | append records as JSON lines to this file |

Run it against the stack's Redis with `REDIS_HOST=localhost`, which is where
`docker-compose.all-services.yml` publishes 6379.

## Logging

Everything inside the program logs through `dev.orwell.logging.Logger` and is handed one at
construction — `ApifyClient`, `InstagramService` and the cache all take it as a parameter and know
nothing about where records end up. `InstaLogger` is the single place a concrete sink is named, so
changing production is configuration, not code.

| Set | Adds |
|---|---|
| *(nothing)* | human-readable console |
| `LOKI_URL` (+ `LOKI_TENANT_ID`) | async batched push to Loki |
| `LOGGING_FILE_NAME` | JSON lines appended to that file |
| `INSTA_LOG_CONSOLE=false` | drops the console sink |

They compose, so a cron job can ship structured records and leave the terminal clean:

```bash
INSTA_LOG_CONSOLE=false LOGGING_FILE_NAME=/var/log/insta.log insta profile nasa
# stdout: the result. stderr: nothing. the file: {"timestamp":…,"level":"INFO",…}
```

Two things differ from the Spring services here, both because this is a program a person runs
rather than a server that boots once:

- **The console sink writes to stderr, not stdout.** Results are stdout and nothing else is, so
  `insta followers nasa --json | jq` stays parseable however chatty logging gets.
- **An unset `LOKI_URL` is not warned about.** The Spring bean complains, because a server with no
  log shipping is usually a misconfigured deployment; for a hand-run command it is the normal case,
  and the warning would fire on every invocation.

A sink that cannot be opened is skipped with a warning rather than being fatal — an unwritable log
file should not be the reason a lookup does not happen — and the whole chain is wrapped in
`FailSafeLogger` so a sink failing mid-run cannot take a lookup down.

The Loki sink batches on a **daemon** thread and flushes every couple of seconds. A program that
exits in under a second would take its unsent records with it, so `InstaLogger` is `AutoCloseable`
and `InstaCli` closes it in a try-with-resources; that drains the queue. This is the one part of
logging a CLI has to get right that a long-lived server does not.

## What this costs

Both default actors are **pay-per-result** with no monthly rental fee, which is what makes them
usable on Apify's free plan — that plan gives $5 of credit a month, does not roll over, and allows
rental Actors on trial only.

| Lookup | Rate | Per run | Free plan covers |
|---|---|---|---|
| `profile` | $2.60 per 1,000 results | 1 result ≈ $0.0026 | ~1,900 lookups/month |
| a 500-account page | from $0.60 per 1,000 results | ≈ $0.30 | ~16 pages/month |

The list rate is the best-tier "from" price, so treat those page counts as optimistic. Free-plan
accounts are also capped at 1,000 results per run on the connections actor — above the 500 ceiling
here, so it does not bite, but raising `INSTA_MAX_LIMIT` past 1,000 would.

`--limit` is passed to Apify as `maxItems`, bounding what a run may charge for, as well as being
applied when mapping — so `INSTA_MAX_LIMIT` is a ceiling no invocation can raise. A run that
outlives its budget is aborted rather than abandoned, because an orphaned run keeps billing.

## How it works

### Layout

```
dev.orwell.insta            InstaCli, InstaEnvs, InstaJson, InstaLogger — the program and its wiring
dev.orwell.insta.apify      ApifyClient, ApifyException, ActorRun — everything that speaks to Apify
dev.orwell.insta.cache      ScrapeCache + Redis and disabled implementations
dev.orwell.insta.instagram  InstagramService and the value types it produces
dev.orwell.insta.graph      Postgres schema, writers, and the sync command
```

The dependency arrows all point one way: `instagram` uses `apify` and `cache`, both of which know
nothing about Instagram, and `InstaCli` wires the three together. Nothing below the root package
knows a command line exists — which is why the same code was a web service last week and could be
one again without touching `apify`, `cache`, or the lookup logic.

Test doubles shared between packages (`ApifyStubServer`, `InMemoryScrapeCache`) live in
`dev.orwell.insta.support`.

### The two lookups

Two actors, because the two questions cost different amounts. The profile actor answers "how
many?" with a single dataset item; the connections actor is billed per account it returns.

They are also fetched differently:

- **`profile`** uses Apify's `run-sync-get-dataset-items`: start, wait, get the dataset, one HTTP
  call. Cheapest possible path. Its limit is Apify's — the run must finish inside 300 seconds.
- **`followers` / `following`** start the run asynchronously and long-poll it, then read the
  dataset and the run's `OUTPUT` record separately. Three or four round trips instead of one, in
  exchange for the two things the sync endpoint cannot give: no 300-second ceiling, and sight of
  `OUTPUT`, which is where the actor puts its continuation token. The sync endpoint returns dataset
  items and nothing else, so paging is simply not visible from it.

Both actor ids are configurable because the Apify store has many interchangeable Instagram
scrapers and they come and go. A replacement must accept the same input shape (documented in
`.env.example`); output field names are read leniently — `full_name` and `fullName`, `verified`
and `is_verified` are all accepted — so casing differences alone do not need a code change.

Only public accounts can be read. Private accounts return no data, by design of the actors and of
Instagram.

**One unverified contract.** The continuation token is read from the run's `OUTPUT` per the
actor's documentation (a `continuations` array carrying `nextContinuationToken`); a bare top-level
token is accepted too. That shape has not been confirmed against a live paid run. If it is wrong,
paging degrades to a single page rather than failing a lookup.

## Tests

```bash
mvn -pl apps/insta -am test
```

The tests run a real local HTTP server standing in for `api.apify.com` — covering the URLs built,
the headers sent, the async start/poll/read sequence, and the failure classification — plus an
ephemeral Testcontainers Redis for the cache's key namespace, expiry, and behaviour when Redis is
down, and argument parsing for every invocation the CLI accepts or refuses. No Apify token and no
network are needed.
