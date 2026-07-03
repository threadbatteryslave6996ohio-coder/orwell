# Bucket Proxy — Issues Found & Fixed

Record of problems discovered while getting the bucket proxy to boot and verifying
the full upload path (client → proxy → bucket) against a local MinIO bucket.

Date: 2026-07-02

---

## 1. `AuthServerClient` had no Spring-usable constructor (startup failure)

**Severity:** Blocker — the proxy could not start.

**Symptom:** On Spring Boot 4, context startup failed because `AuthServerClient`
declared two constructors and none was annotated `@Autowired`. Spring could not
decide which to use and fell back to a non-existent no-arg constructor.

**Fix:** Annotated the production constructor with `@Autowired` so Spring binds
the correct one.

**File:** `src/main/java/dev/clippy/bucket/proxy/AuthServerClient.java`

---

## 2. `ProxyController` used invalid path patterns (startup failure)

**Severity:** Blocker — the proxy could not start.

**Symptom:** The mappings `/metadata/{**key}` and `/delete/{**key}` use Ant-style
`{**name}` syntax that the modern Spring `PathPattern` parser rejects, throwing at
startup.

**Fix:** Changed the captures to `{*key}` (the supported multi-segment form). This
is safe because `S3Service.normalizeKey(...)` already strips the leading slash that
the new parser includes in the captured value.

**File:** `src/main/java/dev/clippy/bucket/proxy/ProxyController.java`

---

## 3. No way to point the proxy at a local / S3-compatible bucket (testing gap)

**Severity:** Enhancement — needed to test without AWS.

**Symptom:** The S3 client was hard-wired to real AWS S3, and every upload forced
`serverSideEncryption("AES256")`. A bare local MinIO has no KMS configured and
rejects SSE-S3, so uploads would fail against it.

**Fix:** Made the S3 client configurable while keeping production defaults intact:

- `ProxyProperties.S3` gained `endpoint`, `pathStyleAccess`, and
  `serverSideEncryption`.
- `S3Service` applies an endpoint override + path-style addressing when an endpoint
  is configured, and only sets server-side encryption when the value is non-blank.
- `application.yml` defaults preserve current behaviour: no endpoint override (real
  AWS) and `server-side-encryption: AES256`. All three are overridable via env vars
  (`PROXY_S3_ENDPOINT`, `PROXY_S3_PATH_STYLE_ACCESS`, `PROXY_S3_SSE`).

**Files:**
- `src/main/java/dev/clippy/bucket/proxy/ProxyProperties.java`
- `src/main/java/dev/clippy/bucket/proxy/S3Service.java`
- `src/main/resources/application.yml`
- `src/test/java/dev/clippy/bucket/proxy/ProxyControllerTest.java` (constructor update)
- `src/test/java/dev/clippy/bucket/proxy/MultipartConfigurationTest.java` (constructor update)

---

## Verification

Stood up a full local stack and exercised the real upload path end-to-end:

- **MinIO** (S3) on `:9000`, bucket `keeboarder-recordings`, console `:9001`
- **PostgreSQL** on `:5433` (auth server database)
- **Auth server** on `:8081`
- **Proxy** on `:5000`, pointed at MinIO + the local auth server (SSE disabled)

Results:

- Proxy health endpoint reported the local bucket and auth server.
- Created an upload identity (`tester`) and logged in through the proxy
  (proxy → auth server → Bearer token).
- Ran the real **syncer** against test recordings: it uploaded a completed screen
  segment directly and a **merged** microphone file; both landed in MinIO at the
  expected keys, and only the current (in-progress) segments remained local.
- Proxy unit tests pass.

See `LOCAL_TESTING.md` and `scripts/local-stack.sh` for the reproducible setup.

---

## Environment note

Docker was initially unusable because the root filesystem was 100% full. Pruning
**stale Docker resources only** (stopped containers, dangling images/volumes)
reclaimed ~13.5 GB. No project files were touched.
