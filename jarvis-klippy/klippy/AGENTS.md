# AGENTS.md

## Project overview

Clippy records clipboard changes from desktop and Android clients in a Spring Boot server backed by PostgreSQL. The main codebase is a Java 25 multi-module Maven project. The Android client is a separate Kotlin/Gradle project under `clients/android`.

Read the root `README.md` and the README for the component being changed before editing it.

## Repository layout

- `server`: Spring Boot clipboard API and persistence.
- `../auth/server`: parent-owned Spring Boot authentication service.
- `../auth/client`: parent-owned shared Java authentication client.
- `utils`: shared environment and logging utilities.
- `clients/client-core`: shared client behavior, configuration, and authentication session code.
- `clients/file-locker`: local offline clipboard storage service.
- `clients/mac`, `clients/linux`, `clients/dummy`, `clients/offline-sync`: Java clients.
- `clients/android`: standalone Android/Kotlin client.
- `scripts`: local build and launch helpers.
- `devops`: Terraform and cloud-init configuration.

## Development rules

- Keep changes scoped to the requested behavior. Do not refactor unrelated code unless it is necessary for correctness.
- Preserve existing public APIs and configuration behavior unless the task explicitly changes them.
- Never commit secrets, tokens, credentials, `.env` contents, generated build output, or IDE state.
- Follow the conventions in the surrounding code. Prefer small, focused methods and explicit error handling.
- Add or update tests for every behavior change and bug fix. A regression test should fail without the fix and pass with it.
- Do not weaken, delete, or skip a test merely to make the build pass. If a test is obsolete because requirements changed, explain why when changing it.
- When shared utilities are changed, check every module that consumes them.
- Update relevant documentation and examples when commands, configuration, APIs, or user-visible behavior change.

## Required workflow after a change

1. Review the complete diff, including tests and documentation. Look for correctness issues, unintended behavior changes, duplication, unclear naming, avoidable complexity, missing edge cases, security problems, and opportunities to simplify the implementation.
2. Run the narrowest relevant tests while developing. For a Maven module, use:

   ```bash
   mvn -pl <module> -am test
   ```

3. Compile/package the affected module and its dependencies:

   ```bash
   mvn -pl <module> -am package
   ```

4. Before declaring the task complete, run the full project checks when the environment supports them:

   ```bash
   mvn test
   mvn package
   ```

5. Re-read the final changed code after tests pass. Improve anything that is unnecessarily complex, fragile, inconsistent, or poorly documented, then rerun the affected checks.
6. Report exactly which tests and builds were run. If a check could not be run or failed because of the environment, state the command and the reason; do not imply it passed.

`mvn package` also runs tests. Do not use `-DskipTests` for final verification unless the user explicitly requests it or the environment makes tests impossible; disclose it if used.

## Test and build prerequisites

- The Maven build requires a full JDK 25+ with `javac`, plus Maven 3.9+.
- Verify toolchain problems with `java -version`, `javac -version`, and `mvn -version` before changing code to work around a build failure.
- Server integration tests use Testcontainers and require a working Docker daemon. Treat Docker-related failures as environment failures only after verifying the cause.
- Services use PostgreSQL and read configuration from the repository-root `.env`, with shell environment variables taking precedence. Do not print or expose secret values while debugging.
- The Android project does not include a Gradle wrapper. Validate Android changes with the installed Gradle/Android Studio toolchain when available, using the relevant test task and a debug build (normally `gradle test` and `gradle assembleDebug` from `clients/android`). If that toolchain is unavailable, state that clearly.

## Review checklist

Before finishing, confirm that:

- The implementation matches the request and handles failure and boundary cases.
- Tests cover the new or fixed behavior, not just the happy path.
- The affected code compiles and relevant tests pass.
- The full Maven build was attempted when practical.
- No secrets, generated files, debug output, dead code, or unrelated formatting changes were introduced.
- Documentation and configuration examples remain accurate.
- The final diff is smaller and clearer where possible without changing scope.
