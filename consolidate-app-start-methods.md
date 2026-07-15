# Potential refactor: consolidate per-app `start` methods into the runner

Status: **future / not scheduled** — captured for later, no action taken yet.

## The question that prompted this

> Why does every app need an explicit `start` method? Can't the runner utility own `start`, or does the start method genuinely differ app to app?

## Findings

There is **already a shared runner**: `packages/server-bootstrap` provides
`AppServer` + `SpringServerBootstrap`. The answer splits by app family.

### Family 1 — Spring apps (boilerplate, collapsible)

Apps: `klippy`, `secrets-manager`, `auth/http-based`, `keeboarder`,
`jarvis/bucket/proxy`, `combined-server`.

Each of these repeats the same triplet:

- `*Launcher.main` — three lines, identical modulo two symbols:
  ```java
  public static void main(String[] args) {
      new AppServer(SecretsManagerEnvs.ENV, SecretsManagerApplication::start).runOrExit(args);
  }
  ```
- `*Application.start(Env)` — delegates to `SpringServerBootstrap.run/start`, identical
  modulo a `Class`, a properties map, a source-name string, and an optional `beforeRun` hook.
- `*Application.start(Map<String,String>)` — verbatim `start(X.from(environment))` overload.

The **only genuinely per-app inputs** are:

1. Env schema (`AuthServerEnvs.ENV`)
2. `springProperties(env)` map — *the real per-app difference*; holds ports, DB creds,
   JPA settings. Should stay per-app.
3. The `@SpringBootApplication` class (for component scanning)
4. A property-source name string (cosmetic)
5. An optional `beforeRun` hook (only `auth` — local-DB notice; `combined` — disclaimer)

### Family 2 — Raw-HTTP apps (genuinely different, do NOT use the runner)

Apps: `alerting` (`AlertServer`), `jarvis/detection` (`DetectionServer`),
`log-analyzer`, and the test-oriented `HttpApiServer`.

These bypass the runner entirely and hand-roll `com.sun.net.httpserver`:

```java
void run() throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);
    server.createContext("/health", this::writeHealth);
    server.createContext("/alerts", this::handleAlert);
    server.start();
}
```

Their start methods genuinely differ (different routes, own socket lifecycle, no Spring
context). They can't fold into the same runner without being ported to Spring or given a
parallel raw-HTTP runner. `combined-server` confirms the split: it merges the six Spring
apps into one context via `@Import`, but alerting/detection are absent — no Spring beans
to merge.

## Implemented change (Spring apps only)

The `Launcher` + `start(Env)` + `start(Map)` triplet is now represented by a direct
`AppServer` constructor and one `AppServerEnv` descriptor, e.g.:

```java
public static final AppServer SERVER = new AppServer(
    ClippyAuthServerApplication.class, "auth-server", AuthServerEnvs.ENV);
```

This deletes the repeated triplet across the six Spring apps. Only the `springProperties`
map legitimately stays per-app.

## Scope / risk notes

- Mechanical refactor across 6 apps; keep `springProperties` maps untouched.
- Leave the raw-HTTP apps (alerting, detection, log-analyzer, HttpApiServer) alone — out of scope.
- Watch `combined-server`: it calls `.start(resolveEnvironment())` rather than `runOrExit`,
  and composes module configs via `@Import` — the descriptor must still expose a plain
  `start(env)` path for it.

## Key files

- `packages/server-bootstrap/src/main/java/dev/orwell/bootstrap/AppServer.java`
- `packages/server-bootstrap/src/main/java/dev/orwell/bootstrap/SpringServerBootstrap.java`
- Launchers/apps under `apps/{klippy,secrets-manager,auth/http-based,keeboarder,jarvis/bucket/proxy,combined-server}/`
- Raw-HTTP outliers: `apps/alerting/.../AlertServer.java`, `apps/jarvis/detection/.../DetectionServer.java`
