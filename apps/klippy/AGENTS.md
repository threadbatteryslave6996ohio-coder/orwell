# AGENTS.md

## Project overview

Klippy records clipboard changes from desktop and Android clients in a Spring Boot server backed by PostgreSQL. The main codebase is a Java 25 multi-module Maven project. The Android client is a separate Kotlin/Gradle project under `clients/android`.

Read the root `README.md` and the README for the component being changed before editing it.

## Repository layout

- `server`: Spring Boot clipboard API and persistence.
- `../auth/http-based/server`: parent-owned Spring Boot authentication service.
- `../auth/http-based/client`: parent-owned shared Java authentication client.
- `utils`: `ClipboardLimits`, the shared 1,000,000-character clipboard content limit.
- `clients/client-core`: shared client behavior, configuration, and authentication session code.
- `clients/file-locker`: local offline clipboard storage service.
- `clients/mac`, `clients/linux`, `clients/dummy`, `clients/offline-sync`: Java clients.
- `clients/android`: standalone Android/Kotlin client.
- `scripts`: local build and launch helpers.

## Review checklist

Before finishing, confirm that:

- The implementation matches the request and handles failure and boundary cases.
- Tests cover the new or fixed behavior, not just the happy path.
- The affected code compiles and relevant tests pass.
- The full Maven build was attempted when practical.
- No secrets, generated files, debug output, dead code, or unrelated formatting changes were introduced.
- Documentation and configuration examples remain accurate.
- The final diff is smaller and clearer where possible without changing scope.
