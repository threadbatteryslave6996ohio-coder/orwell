#!/bin/bash
# start-server.sh - Convenient script to start the Keeboarder server with Redis
#
# One HTTP port serves both the REST API and the WebSocket endpoint (/ws/chat).

set -e

# Default values
SERVER_ADDRESS="${SERVER_ADDRESS:-0.0.0.0}"
SERVER_PORT="${SERVER_PORT:-8025}"
REDIS_HOST="${REDIS_HOST:-localhost}"
REDIS_PORT="${REDIS_PORT:-6379}"

echo "=========================================="
echo "Keeboarder Server Startup"
echo "=========================================="
echo "HTTP API:  http://$SERVER_ADDRESS:$SERVER_PORT/api"
echo "WebSocket: ws://$SERVER_ADDRESS:$SERVER_PORT/ws/chat"
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
export SERVER_ADDRESS SERVER_PORT REDIS_HOST REDIS_PORT
export AUTH_BASE_URL="${AUTH_BASE_URL:-http://localhost:8081}"
java -jar target/keeboarder-server-0.1.0-SNAPSHOT-exec.jar
