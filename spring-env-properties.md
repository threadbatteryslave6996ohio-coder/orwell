# Why the launcher uses Spring environment properties

The launcher has two configuration layers:

1. The environment schema reads values from `.env` files, remote sources, or process
   arguments and validates their types and requiredness.
2. The bootstrap publishes the validated values as Spring environment properties so
   Spring Boot and application components can consume them.

## Standard Spring Boot properties

These are understood directly by Spring Boot:

- `server.address`
- `server.port`
- `logging.file.name`

They control the HTTP bind address, HTTP port, and Spring logging configuration.

## Application-specific properties

These are defined by Orwell rather than Spring Boot:

- `orwell.app.name`
- `orwell.auth.base-url`
- application-specific properties such as `proxy.s3.bucket-name`

They are still published through Spring’s environment because application beans can
then consume them through normal Spring configuration mechanisms such as
`@Value` or `@ConfigurationProperties`.

## Why not pass values directly?

Direct constructor arguments are possible, but they would require manually plumbing
configuration through every component that needs it. They would also bypass Spring
Boot’s built-in server and logging configuration, and make configuration precedence
and testing less consistent.

The environment schema is therefore responsible for validated, typed input, while
Spring’s environment is the bridge used to configure the running application.

There is some duplication between environment variable names such as `SERVER_PORT`
and Spring properties such as `server.port`. This is intentional: the former is the
external launcher configuration contract, while the latter is Spring Boot’s internal
configuration contract. A future cleanup could rely more on Spring’s relaxed binding
for standard variables and explicitly map only custom properties.
