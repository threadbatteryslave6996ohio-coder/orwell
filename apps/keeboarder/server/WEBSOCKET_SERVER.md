# Keeboarder WebSocket Protocol

The wire protocol spoken by the Keeboarder WebSocket endpoint, and the Redis
schema behind it. For building, running, configuring, and the HTTP API, see
[README.md](README.md).

The server maintains a Redis cache of all connected clients and validates client
login tokens against the auth server before registration succeeds.

## Architecture

### Components

1. **KeeboarderServerApplication** - Spring Boot entry point for the server
2. **KeeboarderWebSocketRuntime** - Starts the WebSocket endpoint and owns the Redis client cache
3. **ChatEndpoint** - WebSocket endpoint handler that manages client connections and message routing
4. **RedisClientCache** - Redis client wrapper for managing connected client metadata
5. **Message** - Data class representing client messages

### Message Flow

```
Client logs in to auth server → Gets token for clientId
                           ↓
Client connects → Register with clientId, name, token
                           ↓
        Server validates token with auth server
                           ↓
               Announcement broadcast
            (other clients see new host)
                           ↓
           Client can send/receive messages
                           ↓
         On disconnect → Cleanup Redis entry
```

## Endpoint

The REST API and the WebSocket endpoint share one port, set by `SERVER_ADDRESS`
and `SERVER_PORT`. `WEBSOCKET_CONTEXT_PATH` sets the path prefix and defaults to
`/ws`, so the standalone default endpoint is:

```
ws://host:port/ws/chat
```

The Docker Compose setup in this module sets the prefix to `/keeboarder/ws`,
which makes the endpoint `ws://localhost:8025/keeboarder/ws/chat`.

## Message Protocol

All messages are JSON formatted.

### Client Registration

**Request** (Client → Server):
```json
{
  "type": "register",
  "clientId": "my-keeboarder",
  "name": "MyKeeboarder",
  "token": "token-from-auth-login"
}
```

**Response** (Server → Client):
```json
{
  "type": "registered",
  "clientId": "my-keeboarder",
  "name": "MyKeeboarder"
}
```

If the token is missing or invalid, registration fails and the server closes the session.

### Host Join Announcement

When a new host registers, all other connected clients receive:

```json
{
  "type": "host_joined",
  "clientId": "550e8400-e29b-41d4-a716-446655440000",
  "name": "MyKeeboarder",
  "connectedAt": "2026-06-21T15:30:45.123Z"
}
```

### Personalized Message

**Request** (Client → Server):
```json
{
  "type": "personal",
  "toClientId": "550e8400-e29b-41d4-a716-446655440000",
  "content": "Hello specific client!"
}
```

**Response** (Server → Target Client):
```json
{
  "type": "personal",
  "fromClientId": "sender-id",
  "content": "Hello specific client!"
}
```

### Broadcast Message

**Request** (Client → Server):
```json
{
  "type": "broadcast",
  "content": "Message for everyone"
}
```

**Broadcast** (Server → All Registered Clients):
```json
{
  "type": "broadcast",
  "fromClientId": "sender-id",
  "content": "Message for everyone"
}
```

The sender is not excluded: a broadcast is delivered to every registered
client, including the one that sent it. Only the `host_joined` announcement
excludes its originator.

### Error Messages

```json
{
  "type": "error",
  "message": "Description of what went wrong"
}
```

Auth failures also return:

```json
{
  "type": "auth_failed",
  "reason": "invalid_token"
}
```

## Redis Schema

The server uses Redis to store client information:

- **Key**: `ws:clients` - Set containing all connected client IDs
- **Key**: `ws:client:{clientId}` - Hash containing:
  - `name`: Client's advertised name
  - `connectedAt`: ISO 8601 timestamp of connection

### Example Redis Data

```
127.0.0.1:6379> SMEMBERS ws:clients
1) "550e8400-e29b-41d4-a716-446655440000"
2) "660e8400-e29b-41d4-a716-446655440001"

127.0.0.1:6379> HGETALL ws:client:550e8400-e29b-41d4-a716-446655440000
1) "name"
2) "MyKeeboarder"
3) "connectedAt"
4) "2026-06-21T15:30:45.123Z"
```
