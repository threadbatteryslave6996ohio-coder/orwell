# analyzer

Receives Gmail messages from `gmail-general` at `POST /analyzer/email` and
checks whether the subject contains `login` (case-insensitive).

```bash
mvn -pl apps/analyzer -am package
java -jar apps/analyzer/target/analyzer-0.1.0-SNAPSHOT-exec.jar
```

Configure `GMAIL_WEBHOOK_CLIENTS=http://127.0.0.1:9200/analyzer/email` for the
gmail-general service; that URL assumes `SERVER_PORT=9200`.

The analyzer requires `X-Client-Id` and `Authorization: Bearer <token>` on
incoming messages and validates them with the auth server. `AUTH_BASE_URL` is
**required** — the app exits at startup with a validation error if it is unset.

See [`./.env.example`](./.env.example) for the full set (`SERVER_ADDRESS`,
`SERVER_PORT`, `AUTH_BASE_URL`).
