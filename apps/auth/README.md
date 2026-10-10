# Authentication modules

Authentication is split into a transport-neutral contract and replaceable
implementations:

```text
auth/
├── core/                  AuthenticationStrategy
├── http-based/
│   ├── api/               HTTP request and response DTOs
│   ├── client/            HTTP strategy and login client
│   └── server/            Spring HTTP authentication server
└── in-memory/             Mutable local/test strategy
```

Application cores depend on `auth-core` and accept an
`AuthenticationStrategy`. The standalone Spring configuration wires
`HttpAuthenticationStrategy`, which validates tokens using the configured auth
server URL. The in-memory implementation must be selected explicitly.

## Workspaces

Auth-server routes are scoped to a workspace — `/{workspace}/login`,
`/{workspace}/identities`, `/{workspace}/tokens/check` — and a `clientId` is unique within one.
`def` is the default (`AUTH_WORKSPACE` overrides it, and `HttpAuthenticationStrategy` uses it when
no workspace is given), and `admin` holds the identities allowed to create identities anywhere.
See [`http-based/server/README.md`](http-based/server/README.md) for the rules, the bootstrap
admin, and the migration for a pre-workspace database.

## Token retention

The server collects login tokens that a later login replaced and that nobody has used for a day, so
`client_tokens` stops growing for the life of the deployment. An identity's newest token is never
collected, so this is not expiry and never shortens the life of a token in use. See
[`http-based/server/README.md`](http-based/server/README.md#token-retention).
