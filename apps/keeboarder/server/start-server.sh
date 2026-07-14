#!/bin/bash
# start-server.sh - Convenient script to start the Keeboarder server with Redis
#
# One HTTP port serves both the REST API and the WebSocket endpoint (/ws/chat).

set -e

# Default values
HTTP_HOST="${HTTP_HOST:-0.0.0.0}"
HTTP_PORT="${HTTP_PORT:-8025}"
REDIS_HOST="${REDIS_HOST:-localhost}"
REDIS_PORT="${REDIS_PORT:-6379}"

echo "=========================================="
echo "Keeboarder Server Startup"
echo "=========================================="
echo "HTTP API:  http://$HTTP_HOST:$HTTP_PORT/api"
echo "WebSocket: ws://$HTTP_HOST:$HTTP_PORT/ws/chat"
echo "Redis: $REDIS_HOST:$REDIS_PORT"
echo ""

# Check if Redis is accessible
echo "Checking Redis connection..."
if ! timeout 2 bash -c "echo > /dev/tcp/$REDIS_HOST/$REDIS_PORT" 2>/dev/null; then
  echo "⚠️  Warning: Cannot connect to Redis at $REDIS_HOST:$REDIS_PORT"
  echo "   Make sure Redis is running!"
  echo ""
fi

# Start the server
echo "Starting Keeboarder server..."
export HTTP_HOST HTTP_PORT REDIS_HOST REDIS_PORT
java -jar target/websocket-redis-server-0.1.0-SNAPSHOT-exec.jar
