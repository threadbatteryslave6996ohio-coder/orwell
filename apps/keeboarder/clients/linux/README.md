# Keeboarder Linux Client

This module is a Java client that connects to the Keeboarder WebSocket server
and streams Linux keyboard events.

## Scope

- Global keyboard capture is supported for X11 sessions.
- Wayland is not supported for global capture. The client fails explicitly
  when started from a Wayland or headless session.

## Build

```bash
mvn -pl apps/keeboarder/clients/linux -am package
```

## Run

Set environment variables or put them in the repository `.env` file:

```dotenv
KEEBOARDER_SERVER_URL=ws://localhost:8025/ws/chat
KEEBOARDER_AUTH_BASE_URL=http://localhost:8081
KEEBOARDER_CLIENT_ID=my-linux
KEEBOARDER_CLIENT_SECRET=change-me-please
KEEBOARDER_CLIENT_NAME=My-Linux
```

`KEEBOARDER_CLIENT_ID` and `KEEBOARDER_CLIENT_SECRET` are required. `KEEBOARDER_CLIENT_NAME`
defaults to `Linux-<hostname>` when omitted. `KEEBOARDER_SERVER_URL` defaults to
`ws://localhost:8025/ws/chat` and `KEEBOARDER_AUTH_BASE_URL` defaults to
`http://localhost:8081`.

Then run:

```bash
java -jar apps/keeboarder/clients/linux/target/keeboarder-linux-client-0.1.0.jar
```

## Behavior

- Logs in to the auth server first using `clientId` and `clientSecret`.
- Registers with the websocket server using `type=register`, `clientId`, `name`, and the issued token.
- Sends each key press and release as a `broadcast` message.
- Places the actual key event data in the `content` field as JSON.

## Notes

- Start the client from a logged-in X11 desktop session with `DISPLAY` set.
- The combined server serves auth over `/auth`, but the websocket listener
  still uses its own port, typically `8025`.
