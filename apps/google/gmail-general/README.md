# gmail-general

Receives Gmail API Pub/Sub notifications, fetches newly added Inbox messages,
saves each message as `GMAIL_STORE_DIR/<message-id>.json`, and POSTs each saved
message to the comma-separated `GMAIL_WEBHOOK_CLIENTS` list.

Before every client webhook delivery, gmail-general calls the auth server's
`/login` endpoint with `AUTH_CLIENT_ID` and `AUTH_CLIENT_SECRET`, then sends the
message with the returned bearer token and `X-Client-Id`. The receiving app
checks those headers with the auth server. The external Pub/Sub endpoint is
not protected by this internal auth protocol; secure it with authenticated
Pub/Sub push/IAM.

Build and run:

```bash
mvn -pl apps/google/gmail-general -am package
java -jar apps/google/gmail-general/target/gmail-general.jar
curl -X POST http://127.0.0.1:9100/gmail/watch
```

Before starting, create the Pub/Sub topic/subscription, grant Gmail publish
permission to `gmail-api-push@system.gserviceaccount.com`, and provide an OAuth
access token with Gmail read access. The `/gmail/watch` endpoint registers the
mailbox watch. The access token must be refreshed by the caller when it expires;
the placeholder refresh-token variables are reserved for that integration.
