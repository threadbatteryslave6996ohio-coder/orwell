# analyzer

Receives Gmail messages from `gmail-general` at `POST /analyzer/email` and
checks whether the subject contains `login` (case-insensitive).

```bash
mvn -pl apps/analyzer -am package
java -jar apps/analyzer/target/analyzer.jar
```

Configure `GMAIL_WEBHOOK_CLIENTS=http://127.0.0.1:9200/analyzer/email` for the
gmail-general service. The analyzer requires `X-Client-Id` and
`Authorization: Bearer <token>` on incoming messages and validates them with
the auth server. Set `AUTH_SERVER_URL` accordingly.
