# Keeboarder Mac Client

This module is a Java client that connects to the Keeboarder WebSocket server and streams macOS keyboard events.

## Build

```bash
cd /home/kamina_goat/Desktop/keeboarder-parent
mvn -f ../../../pom.xml -pl apps/auth/http-based/client -am install
mvn -f clients/mac/pom.xml package
```

## Run

```bash
java -jar target/keeboarder-mac-client-0.1.0.jar \
  --server-url ws://localhost:8025/ws/chat \
  --auth-base-url http://localhost:8081 \
  --client-id my-mac \
  --client-secret change-me-please \
  --name My-Mac
```

## Behavior

- Logs in to the auth server first using `clientId` and `clientSecret`.
- Registers with the websocket server using `type=register`, `clientId`, `name`, and the issued token.
- Sends each key press and release as a `broadcast` message.
- Places the actual key event data in the `content` field as JSON.

## Test

```bash
cd /home/kamina_goat/Desktop/keeboarder-parent
mvn -f ../../../pom.xml -pl apps/auth/http-based/client -am install
mvn -f clients/mac/pom.xml package
```

## Notes

- macOS requires Accessibility permissions for global key capture.
- `--client-id` and `--client-secret` are required. You can also provide them with `KEEBOARDER_CLIENT_ID` and `KEEBOARDER_CLIENT_SECRET`.
- Use `KEEBOARDER_SERVER_URL`, `KEEBOARDER_AUTH_BASE_URL`, and `KEEBOARDER_CLIENT_NAME` to set defaults.
- If a test environment blocks local sockets, Maven test runs that start local servers can fail even when the code compiles correctly.
