# need to employ an apps and packages structure

## Deferred architecture work

- Replace combined-server package scanning and concrete controller imports with explicit, public module configuration entry points and narrow service interfaces.
- Evaluate whether `streaming` and `proxy` should remain separate modules or be merged — if shared deployment is the common case, make merging trivial (e.g., share config and wiring so they can start in the same process).


