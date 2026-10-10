# server-test-support

Test scaffolding that more than one module needs. Its classes live in `src/main` at compile scope
on purpose — they are consumed from *other* modules' `src/test`, which a test-jar would make
fiddlier than it is worth.

Depend on it with `<scope>test</scope>`:

```xml
<dependency>
    <groupId>dev.orwell</groupId>
    <artifactId>server-test-support</artifactId>
    <scope>test</scope>
</dependency>
```

## PostgresIntegrationTest

Base class for a Spring Boot integration test that needs a real Postgres. It starts one
Testcontainers instance per test class and wires it into `spring.datasource.*` with
`create-drop` schema management, so a subclass writes no container plumbing:

```java
class SecretsManagerIntegrationTest extends PostgresIntegrationTest {
    // @DynamicPropertySource here for anything else the test needs
}
```

The container binds no fixed port, which is what keeps it clear of the repo's one shared Postgres
in `docker-compose.all-services.yml`.

This base is deliberately Spring-only: a test with no Spring context has no
`spring.datasource.*` properties for it to register.

Everything here carries Spring and Testcontainers with it. Helpers that need neither live in
[`packages/test-support`](../test-support/README.md), so a desktop client's tests can use those
without inheriting any of this; a module takes whichever it needs, and several take both.

## PostgresJdbcTest

The counterpart for exactly those tests. It starts the same per-class container and adds the
`dataSource()` / `connection()` / `execute(...)` / `query(...)` helpers each of them was otherwise
writing for itself:

```java
class WorkspaceSchemaMigrationTest extends PostgresJdbcTest {
    @BeforeEach
    void dropEverything() throws SQLException {
        execute("DROP TABLE IF EXISTS client_tokens", "DROP TABLE IF EXISTS client_identities");
    }
}
```

The two bases are separate on purpose, and the split is not only about properties: `create-drop`
builds the *current* schema, which is exactly wrong for a test whose subject is a migration — there
would be nothing left to migrate. Tests here stand the old table up by hand instead.

Helpers are static because the container is, so a subclass's own `@BeforeAll` can use them.
`dataSource()` opens a connection per call rather than pooling, and is not Spring's
`DriverManagerDataSource` — that would put `spring-jdbc` on the test classpath of every consumer
for the sake of one class.

`RetentionSweeperTest` and insta's `GraphTest` use it too — `GraphTest` as an abstract base of its
own, so its subclasses inherit the container through it.

## ObjectProviders

`ObjectProviders.of(value)` builds an `ObjectProvider<T>` around a value already in hand.

The repo's request-scoped beans — `AuthenticationContext` above all — are injected as
`ObjectProvider<T>` so a controller can be constructed once and still see the current request's
value. A test that calls `new SomeController(...)` directly rather than booting a context has to
supply one, and the interface has five methods to satisfy for what is conceptually a constant.
Hand-writing that stub is how klippy-server, keeboarder-server and object-storage-proxy ended up
with the same thirty lines each.

```java
var controller = new KeeboarderController(
        new KeeboarderService(),
        ObjectProviders.of(AuthenticationContext.authenticated("client")));
```

An unauthenticated caller is `ObjectProviders.of(AuthenticationContext.unauthenticated())`, not an
absent bean — the request-scoped bean is always there, and it is the context inside it that says
whether anyone is authenticated.

It is generic on purpose and knows nothing about `AuthenticationContext`, so this module needs no
dependency on `auth-core` — only `spring-beans`, for the interface itself.
