# scripts

Two operational scripts. Both carry usage details in their own header comments — this file only
covers the context that isn't in the code.

## `redeploy-on-update.sh`

Fast-forwards the checkout to `origin/main` and rebuilds the live docker-compose stack if there
are new commits.

**This is a deploy-host entry point, not a developer convenience.** It is meant to be wired to a
cron entry or systemd timer on the specific host that runs the stack, where it polls on an
interval — nothing in the script or the repo registers that timer, so it is set up out of band.
Running it on a dev machine will rebuild and restart whatever stack that machine has.

It refuses to run if the checkout has diverged from the branch, and exits 0 without touching the
stack when there is nothing new.

## `seed-secrets.sh`

Populates a running secrets manager with the env vars used across the repo, grouped per service
and collected into bundles.

**Ordering constraint:** it authenticates as an existing admin, so the secrets manager must
already be up *and* an admin user must already exist before this runs. The script cannot create
that user. Export `SECRETS_ADMIN_TOKEN` (a bearer token for that admin) before invoking it;
without it the script exits immediately.
