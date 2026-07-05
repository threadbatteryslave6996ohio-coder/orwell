# Removing Redundant Code

## HIGH — exact or near-exact copies

| # | Duplication | Location | Fix |
|---|---|---|---|
| 1 | **CooldownTracker** — byte-for-byte identical | `apps/jarvis/detection/src/main/java/dev/orwell/bucket/detection/CooldownTracker.java` and `apps/jarvis/bucket/alerting/src/main/java/dev/orwell/bucket/alerting/CooldownTracker.java` | Extract into shared package |
| 2 | **AuthenticationStrategy bean config** — 5 near-identical `@Bean` methods | `apps/klippy/server/.../AuthClientConfiguration.java`, `apps/jarvis/bucket/proxy/.../AuthenticationConfiguration.java`, `apps/keeboarder/server/.../KeeboarderServerConfiguration.java`, `apps/combined-server/.../CombinedAuthenticationConfiguration.java`, `apps/secrets-manager/server/.../AuthClientConfiguration.java` | Merge into single shared config |
| 3 | **Server DB dependencies** — exact 7-dependency block | `apps/klippy/server/pom.xml`, `apps/auth/http-based/server/pom.xml`, `apps/secrets-manager/server/pom.xml` | Push into parent dependencyManagement |
| 4 | **Spring Boot plugin config** — identical structure, different mainClass | Same 3 server pom.xmls | Push into parent pluginManagement |
| 5 | **Combined*DatabaseConfiguration** — 3 classes, same HikariCP+Hibernate template, different property prefixes | `apps/combined-server/.../CombinedAuthDatabaseConfiguration.java`, `CombinedClipboardDatabaseConfiguration.java`, `CombinedSecretsDatabaseConfiguration.java` | Parameterize into single class |
| 6 | **Combined*ModuleConfiguration** — 5 classes, same @ComponentScan+exclude pattern | `apps/combined-server/.../CombinedAuthModuleConfiguration.java`, `CombinedClipboardModuleConfiguration.java`, `CombinedSecretsModuleConfiguration.java`, `CombinedJarvisModuleConfiguration.java`, `CombinedKeeboarderModuleConfiguration.java` | Parameterize into single class |
| 7 | **Secrets manager DTOs** — triplicated response records (admin/accessor/client) | `apps/secrets-manager/server/.../admin/*Response.java`, `accessor/*Response.java`, `client/dto/*.java` | Merge into shared records |
| 8 | **Create/Update request DTOs** — near-identical (name + description) | `apps/secrets-manager/server/.../admin/CreateGroupRequest.java`, `CreateBundleRequest.java`, `UpdateGroupRequest.java`, `UpdateBundleRequest.java` | Merge into shared |

## MEDIUM

| # | Duplication | Location | Fix |
|---|---|---|---|
| 9 | **Launcher classes** — same `main() → load envs → start app` delegation | `apps/klippy/server/.../ClippyServerLauncher.java`, `apps/auth/http-based/server/.../ClippyAuthServerLauncher.java`, `apps/secrets-manager/server/.../SecretsManagerLauncher.java` | Extract shared launcher base |
| 10 | **springProperties()** — same 8 property puts, different env var names | `apps/klippy/server/.../ServerEnvs.java`, `apps/auth/http-based/server/.../AuthServerEnvs.java`, `apps/secrets-manager/server/.../SecretsManagerEnvs.java` | Extract shared env utility |
| 11 | **bearerToken() / extractBearerToken()** — identical bearer parsing logic | `apps/klippy/server/.../ClipboardEntryController.java`, `apps/secrets-manager/server/.../AuthValidator.java` | Extract into shared utility |
| 12 | **Integration test HTTP helpers** — same `post()`/`get()` pattern | `apps/klippy/server/src/test/.../ClipboardEntryHttpIntegrationTest.java`, `apps/combined-server/src/test/.../CombinedServerHttpIntegrationTest.java` | Extract shared test utility |
| 13 | **Maven shade plugin** — repeated unconfigured in 4 client poms | `apps/klippy/clients/linux/pom.xml`, `mac/pom.xml`, `dummy/pom.xml`, `offline-sync/pom.xml` | Declare once in parent pluginManagement |
| 14 | **pom.xml dependencyManagement** — re-declares what root already has | `apps/auth/pom.xml`, `apps/secrets-manager/pom.xml` | Remove redundant declarations |
| 15 | **pom.xml pluginManagement** — same re-declaration issue | `apps/auth/pom.xml`, `apps/secrets-manager/pom.xml` | Remove redundant declarations |

## LOW

| # | Issue | Location | Fix |
|---|---|---|---|
| 16 | **.gitignore files** — inconsistent across submodules, root already covers `**/target/` | Multiple submodule `.gitignore` files | Remove sub-module ones |
| 17 | **Secrets controllers** — `@RequestHeader` auth extraction repeated ~20x | `apps/secrets-manager/server/.../SecretsAdminController.java`, `SecretsAccessorController.java` | Method-level annotation or base class |
| 18 | **Combined module configs** — mixing `@ComponentScan`, `@EnableConfigurationProperties`, `@Import` inconsistently | `apps/combined-server/` | Standardize approach |
