# scripts

Two scripts. Both carry usage details in their own header comments — this file only covers the
context that isn't in the code.

## `stack.sh`

Runs `docker compose` against both compose files. `./scripts/stack.sh` with no arguments is
`up -d`; anything else is passed through, so `logs -f auth-server`, `ps`, `down` and
`up -d --build` all work.

**Why it exists.** The stack is two files — `docker-compose.storages.yml` (Postgres, Redis, MinIO)
and `docker-compose.all-services.yml` (the applications) — and *every* subcommand needs both, not
just `up`: the applications `depends_on` `db` and `redis`, so Compose refuses the applications file
alone with "depends on undefined service". Forgetting the second `-f` is the easy mistake, and this
is the whole of what the script prevents. It prints the command it runs, so nothing is hidden.

`--storages-only` starts just the storage half, which is the supported way to run a service from
your IDE or a jar by hand against real Postgres.

`rebuild-and-run-docker.sh` at the repo root is an alias for `stack.sh up -d --build
--remove-orphans`.

## `redeploy-on-update.sh`

Fast-forwards the checkout to `origin/main` and rebuilds the live docker-compose stack if there
are new commits.

**This is a deploy-host entry point, not a developer convenience.** It is meant to be wired to a
cron entry or systemd timer on the specific host that runs the stack, where it polls on an
interval — nothing in the script or the repo registers that timer, so it is set up out of band.
Running it on a dev machine will rebuild and restart whatever stack that machine has.

It refuses to run if the checkout has diverged from the branch, and exits 0 without touching the
stack when there is nothing new.

## There is no secrets-manager seed script

`seed-secrets.sh` **was deleted deliberately** — don't recreate it, and ignore references to it in
older commits.

It created the secrets manager's groups, envs and bundles from a hardcoded inventory of every
variable in the repo, and it had gone wrong in a way that was not worth repairing: it aborted
partway through on a variable renamed out from under it, it was not re-runnable after that (the
admin API answers `409` for a group that already exists, and `curl -sf` under `set -e` stops the
script), and 44 of the envs it created reached no bundle at all — so five services' configuration
was seeded into a place nothing loads from.

A fresh secrets manager is populated through its own `/admin` API or UI. Nothing else in the repo
depended on the script: `EnvLoader`'s `url` mode fetches a bundle by id and does not care how that
bundle came to exist.
