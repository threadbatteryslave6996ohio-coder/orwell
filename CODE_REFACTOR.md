# need to employ an apps and packages structure

## Deferred architecture work

- Put authentication for every Java server behind a shared authentication service interface. Keep HTTP/auth-server-client adapters for deployed services and allow callers to depend on the interface rather than a concrete client.
- Replace combined-server package scanning and concrete controller imports with explicit, public module configuration entry points and narrow service interfaces.



