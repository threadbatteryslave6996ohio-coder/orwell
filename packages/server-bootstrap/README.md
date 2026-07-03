# Spring Server Bootstrap

Shared Spring Boot startup wiring for server applications across the repository.
The package applies an already-resolved property map as both default properties
and a highest-precedence named property source before starting the requested
application class.

Environment discovery, validation, logging configuration, and application-specific
startup behavior remain owned by each server.

## Maven dependency

```xml
<dependency>
    <groupId>dev.clippy</groupId>
    <artifactId>clippy-server-bootstrap</artifactId>
    <version>${project.version}</version>
</dependency>
```
