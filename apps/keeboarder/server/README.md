# keeboarder

A multi-protocol keyboard and communication system.

Java clients are in `apps/keeboarder/clients/`. The macOS client is under `apps/keeboarder/clients/mac`.

## 🎹 WebSocket Server

A Java-based WebSocket server with Redis-backed client management and auth-server-backed client authentication.

Both the WebSocket registration flow and HTTP API require credentials issued by
the auth server configured through `CLIPPY_AUTH_BASE_URL`. HTTP callers send
`Authorization: Bearer <token>` and `X-Client-Id: <clientId>` headers.

**[→ Full WebSocket Server Documentation](./WEBSOCKET_SERVER.md)**

### Quick Start

1. **Start Redis:**
   ```bash
   docker run -d -p 6379:6379 redis:latest
   ```

2. **Install the auth client locally if building this module on its own:**
   ```bash
   cd ..
   mvn -f ../../pom.xml -pl apps/auth/http-based/client -am install
   cd server
   ```

3. **Build the server:**
   ```bash
   mvn -DskipTests package
   ```

4. **Run the server:**
   ```bash
   java -jar target/websocket-redis-server-0.1.0-jar-with-dependencies.jar
   ```

5. **Test the server:**
   - Use the macOS client in `apps/keeboarder/clients/mac`

### Build And Test

From the repo root:

```bash
mvn -f ../../pom.xml -pl apps/auth/http-based/client -am install
mvn -f server/pom.xml test
```

In restricted sandboxes, tests that open local sockets may fail with `java.net.SocketException: Operation not permitted`.

### Features

- ✅ Real-time WebSocket communication
- ✅ Redis-backed client registry
- ✅ Personalized message delivery
- ✅ Host advertisement and discovery
- ✅ Broadcast messaging

### Message Protocol

All communication uses JSON. Connect to `ws://localhost:8025/ws/chat`:

```javascript
// Register
{ type: 'register', clientId: 'my-device', name: 'My Device', token: '<login token>' }

// Send personal message
{ type: 'personal', toClientId: 'them', content: 'Hello' }

// Send broadcast
{ type: 'broadcast', content: 'Hello everyone' }
```

See [WEBSOCKET_SERVER.md](./WEBSOCKET_SERVER.md) for complete documentation.
