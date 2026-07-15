# Spring Server Bootstrap

Shared Spring Boot startup wiring for server applications across the repository.

## Usage

1. Create a launcher class that resolves environment variables (e.g. from `.env` files).
2. Call `SpringServerBootstrap.start(applicationClass, envMap)`.
3. The bootstrap applies the env map as Spring properties and starts the application.

`AuthenticationStrategyConfiguration` is auto-configured: it provides an
`HttpAuthenticationStrategy` bean wired to `orwell.auth.base-url`. Modules that
need a different strategy can define their own `@Bean`; it takes precedence via
`@ConditionalOnMissingBean`.

`HealthEndpointConfiguration` is also auto-configured: it provides a shared
`HealthDetailsProvider` bean per app boundary for servlet apps. Apps
contribute their own prefixed `GET /health` controllers and can add extra
fields by overriding the matching named bean:

- `jarvisHealthDetailsProvider`
- `clippyHealthDetailsProvider`
- `secretsHealthDetailsProvider`

## Maven dependency

```xml
<dependency>
    <groupId>dev.orwell</groupId>
    <artifactId>clippy-server-bootstrap</artifactId>
    <version>${project.version}</version>
</dependency>
```

## Key classes

| Class | Purpose |
|---|---|
| `SpringServerBootstrap` | Entry point: `start(Class<?>, Map<String, String>)` |
| `AuthenticationStrategyConfiguration` | Provides default `AuthenticationStrategy` bean |
| `HealthEndpointConfiguration` | Provides default named `HealthDetailsProvider` beans |
