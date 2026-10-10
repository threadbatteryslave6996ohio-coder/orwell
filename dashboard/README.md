# dashboard

A small development UI for poking at the orwell services from a browser — logging in against the
auth server, browsing secrets, and reading clipboard entries. It is a dev tool, not a deployed
service.

## Run

```bash
python3 dashboard/server.py
```

Then open <http://localhost:9187>. No dependencies beyond the Python 3 standard library.

The Auth tab's three cards each take a workspace (`def` unless you change it), since auth routes
are `/{workspace}/login`, `/{workspace}/identities` and `/{workspace}/tokens/check`. **Register
identity** additionally needs an admin session: log in as an identity in the `admin` workspace
first, save the session, then pick it in the card's dropdown — the auth server refuses
provisioning from anything else. See
[`apps/auth/http-based/server/README.md`](../apps/auth/http-based/server/README.md) for where the
first admin comes from.

## What `server.py` does

It serves `dashboard/static/` at `/` and reverse-proxies `/api/<service>/*` to the real backends.
The proxy exists because none of auth-server, secrets-manager-server, or klippy-server set CORS
headers, so the dashboard's browser JS cannot call them directly from a different origin. Going
through the server side takes the browser's CORS check out of the picture entirely.

## Prerequisites

The backend ports are hardcoded, so those services must already be running on exactly these:

| Proxy path | Backend |
|---|---|
| `/api/auth` | `http://localhost:8081` (auth server) |
| `/api/secrets` | `http://localhost:8083` (secrets manager) |
| `/api/klippy` | `http://localhost:8082` (klippy server) |

A backend that is down or on a different port surfaces as a `502` from the proxy. Change the
`BACKENDS` map at the top of `server.py` if your ports differ.
