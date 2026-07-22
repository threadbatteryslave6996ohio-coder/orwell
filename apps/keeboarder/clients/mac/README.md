# Keeboarder Mac Client

This module is a Java client that connects to the Keeboarder WebSocket server
and streams macOS keyboard events.

## Build

```bash
mvn -pl apps/keeboarder/clients/mac -am package
```

## Run

Set environment variables or put them in the repository `.env` file:

```dotenv
KEEBOARDER_SERVER_URL=ws://localhost:8025/ws/chat
KEEBOARDER_AUTH_BASE_URL=http://localhost:8081
KEEBOARDER_CLIENT_ID=my-mac
KEEBOARDER_CLIENT_SECRET=change-me-please
KEEBOARDER_CLIENT_NAME=My-Mac
```

`KEEBOARDER_CLIENT_ID` and `KEEBOARDER_CLIENT_SECRET` are required.
`KEEBOARDER_CLIENT_NAME` defaults to `Mac-<hostname>` when omitted.
`KEEBOARDER_SERVER_URL` defaults to `ws://localhost:8025/ws/chat` and
`KEEBOARDER_AUTH_BASE_URL` defaults to `http://localhost:8081`.

Then run:

```bash
java -jar apps/keeboarder/clients/mac/target/keeboarder-mac-client-0.1.0-SNAPSHOT.jar
```

## Behavior

- Logs in to the auth server first using `clientId` and `clientSecret`.
- Registers with the websocket server using `type=register`, `clientId`, `name`, and the issued token.
- Sends each key press and release as a `broadcast` message.
- Places the actual key event data in the `content` field as JSON.

## Test

```bash
mvn -pl apps/keeboarder/clients/mac -am test
```

## Notes

- macOS requires Accessibility permissions for global key capture.
- If a test environment blocks local sockets, Maven test runs that start local servers can fail even when the code compiles correctly.
