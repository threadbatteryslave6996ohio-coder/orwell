#!/bin/bash
# start-server.sh - Convenient script to start the WebSocket server with Redis

set -e

# Default values
WEBSOCKET_HOST="${WEBSOCKET_HOST:-0.0.0.0}"
WEBSOCKET_PORT="${WEBSOCKET_PORT:-8025}"
REDIS_HOST="${REDIS_HOST:-localhost}"
REDIS_PORT="${REDIS_PORT:-6379}"
SERVER_NAME="${SERVER_NAME:-KeeboarderWS}"

echo "=========================================="
echo "Keeboarder WebSocket Server Startup"
echo "=========================================="
echo "WebSocket: ws://$WEBSOCKET_HOST:$WEBSOCKET_PORT/ws/chat"
echo "Redis: $REDIS_HOST:$REDIS_PORT"
echo "Server Name: $SERVER_NAME"
echo ""

# Check if Redis is accessible
echo "Checking Redis connection..."
if ! timeout 2 bash -c "echo > /dev/tcp/$REDIS_HOST/$REDIS_PORT" 2>/dev/null; then
  echo "⚠️  Warning: Cannot connect to Redis at $REDIS_HOST:$REDIS_PORT"
  echo "   Make sure Redis is running!"
  echo ""
fi

# Start the server
echo "Starting WebSocket server..."
export WEBSOCKET_HOST WEBSOCKET_PORT REDIS_HOST REDIS_PORT SERVER_NAME
java -jar target/websocket-redis-server-0.1.0-jar-with-dependencies.jar
