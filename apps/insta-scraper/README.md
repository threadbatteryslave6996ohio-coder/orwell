# insta-scraper

The service behind [`insta`](../insta/README.md)'s **`self-hosted`** connections source. Two routes
and a cookie jar: it reads follower and following lists straight from Instagram's private web API
and serves them in the shape insta's self-hosted contract documents.

```bash
mvn -pl apps/insta-scraper -am package
java -jar apps/insta-scraper/target/insta-scraper-0.1.0-SNAPSHOT-exec.jar
# insta-scraper on 127.0.0.1:8099
```

| Route | |
|---|---|
| `GET /connections?username=&type=&limit=&cursor=` | one page of a follower or following list |
| `GET /health` | liveness, plus how much of the hourly budget is spent |

## Read this before running it

**This will get rate-limited and eventually flagged.** That is inherent, not a bug waiting to be
fixed here — absorbing it is exactly what the paid Apify actors charge for. Two consequences worth
accepting up front:

- **Use a burner Instagram account.** `sessionid` *is* the login: whoever holds it is signed in as
  that account until it is revoked.
- **Keep a paid source behind this one in insta's chain**, so a refusal costs money rather than
  costing a walk:
  ```bash
  APIFY_CONNECTIONS_ACTORS=self-hosted,scraping-solutions
  ```

There is no API key to apply for. Instagram's Graph API does not expose follower lists to anyone,
so this drives the same endpoints a browser does, which means being logged in as somebody.

## Why a service and not a source inside insta

One reason, and it is enough: **insta is a CLI run from cron, so it cannot remember it made four
hundred Instagram requests forty minutes ago.** A scraper that cannot remember that gets its
account banned. A process that stays up can — see [Rate limiting](#rate-limiting).

Keeping Instagram credentials out of insta is a welcome side effect rather than the motive. insta
holds no session and never has; the only other thing that would change that is the opt-in
`logical-scrapers` adapter, which hands cookies to a third party.

It deliberately does **not** depend on the `insta` module. The contract between them is the HTTP
shape below, not a shared Java type — which is what lets this be replaced by an implementation in
another language without touching insta at all.

## Setting up the session

From a logged-in browser: DevTools → Application → Cookies → `instagram.com`.

| Variable | |
|---|---|
| `INSTA_SCRAPER_SESSIONID` | **required** — the login itself |
| `INSTA_SCRAPER_DS_USER_ID` | the logged-in account's id |
| `INSTA_SCRAPER_CSRF_TOKEN` | sent as a cookie and as `X-CSRFToken` |
| `INSTA_SCRAPER_EXTRA_COOKIES` | `mid`, `ig_did` — as `name=value; name=value` |
| `INSTA_SCRAPER_USER_AGENT` | must match the browser the cookies came from |
| `INSTA_SCRAPER_APP_ID` | `936619743392459`; without it the endpoints answer 400 |

The device cookies matter more than they look. Keep them stable across requests — a session whose
device changes on every call is the pattern fraud detection is built to find.

`INSTA_SCRAPER_SESSIONID` is `required` rather than optional on purpose: without a session this
service can do nothing but return errors, and a process that starts, looks healthy and fails every
request is worse than one that refuses to start. insta would silently fail over to a paid actor for
every walk, and the bill would be the only symptom.

Nothing here is ever logged. Startup says `sessionid set, 4 cookies, app id …` and no more.

## Rate limiting

Two halves, because either alone is insufficient:

| | Default | |
|---|---|---|
| `INSTA_SCRAPER_MIN_INTERVAL_MILLIS` | `2000` | spacing, so a burst never happens |
| `INSTA_SCRAPER_HOURLY_REQUESTS` | `200` | a ceiling however patiently requests are spaced |

An interval alone will happily make 1,800 requests an hour at two seconds apart and get the account
flagged for it. At 50 accounts per page, the defaults are roughly 10,000 accounts an hour of
theoretical throughput, which you will not reach — treat them as a safety rail, and raise them only
knowing you are spending the account.

**The interval is waited out; the cap is not.** Exceeding the cap returns `429` immediately so
insta fails over within the second. Blocking for the rest of the hour instead would hold insta's
socket open until it timed out — and a timeout is the one failure insta deliberately does *not*
fail over on, so the walk would stall rather than move to a paid source.

Your IP matters more than any of this. A datacenter address gets soft-blocked far sooner than a
residential one, which is usually the single biggest factor in how long a session survives.

## The contract

```
GET /connections?username=nasa&type=followers&limit=500[&cursor=…]
Authorization: Bearer {INSTA_SCRAPER_TOKEN}      (only when a token is configured)

200 {"accounts":[{"id":"1","username":"alice","fullName":"Alice",
                  "isPrivate":false,"isVerified":false,"profilePicUrl":"…"}],
     "nextCursor":"QVFB…"}          // null = the list is exhausted
```

One call collects Instagram pages until it has `limit` accounts, runs out of list, or spends
`INSTA_SCRAPER_MAX_SECONDS_PER_REQUEST`. Instagram's own page is about 50, so doing that loop here
rather than in insta saves a round trip per 50 accounts without changing what either side means.

### The cursor is the whole contract

Returned non-null it means "more remain". Null means "that was all of them" — and insta takes null
as licence to **retire every follower absent from the walk**. So stopping early for any reason,
limit reached or time budget spent, returns the cursor we stopped at. The only thing that produces
a null cursor is Instagram's own `next_max_id` being absent.

### Errors never degrade into an empty list

This is the rule the whole service is shaped around. Three services downstream, an empty list is
recorded as every follower leaving at once.

| Situation | Status | What insta does |
|---|---|---|
| session refused, checkpointed, HTML instead of JSON | `401` | fails over to the next source |
| hourly budget spent, or Instagram throttling | `429` + `Retry-After` | fails over |
| no `users` array, unreachable, unparseable | `502` | fails over |
| no such account | `404` | stops the walk |
| bad `username` or `type` | `400` | stops the walk |

A dead session answers `302` to the login page, and redirects are deliberately **not followed** —
followed, that becomes a `200` of HTML and reads as a parse problem; unfollowed it stays the
expired session it is.

## Configuration

| Variable | Default | |
|---|---|---|
| `SERVER_ADDRESS` / `SERVER_PORT` | `127.0.0.1` / `8099` | where it binds |
| `INSTA_SCRAPER_TOKEN` | *(empty)* | must match insta's `INSTA_SELFHOSTED_TOKEN`; blank disables the check |
| `INSTA_SCRAPER_BASE_URL` | `https://www.instagram.com` | overridable so tests can point at a stub |
| `INSTA_SCRAPER_PAGE_SIZE` | `50` | Instagram's page size |
| `INSTA_SCRAPER_MAX_SECONDS_PER_REQUEST` | `40` | keep under insta's `INSTA_HTTP_TIMEOUT_SECONDS` |
| `LOGGER` / `LOKI_URL` / `LOKI_TENANT_ID` | *(empty)* | the usual sink selection |

Plus the session and rate-limit variables above. See [`./.env.example`](./.env.example).

**Bind to `127.0.0.1` or set a token.** Unauthenticated, this endpoint hands out follower graphs to
anything that can reach the port. It logs a warning on every start when no token is set.

## Tests

```bash
mvn -pl apps/insta-scraper -am test
```

A real local HTTP server stands in for `www.instagram.com`, covering the URLs built, the session
headers sent, and every refusal above — with the heaviest weight on the ones that must not become
an empty page. No Instagram session and no network are needed.
