# Clippy Server Bootstrap

Shared Spring Boot startup wiring for the clipboard, auth, and combined server
applications. The module applies an already-resolved property map as both
default properties and a highest-precedence named property source before
starting the requested application class.

Environment discovery, validation, logging configuration, and module-specific
startup behavior remain owned by each server.
