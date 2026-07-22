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
