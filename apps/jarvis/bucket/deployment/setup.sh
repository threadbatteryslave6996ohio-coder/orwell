#!/bin/bash
set -euo pipefail

export DEBIAN_FRONTEND=noninteractive

APP_ROOT="/opt/jarvis"
SOURCE_DIR="$APP_ROOT/source"
PROXY_DIR="/opt/s3-proxy"
STREAM_ROOT="/opt/streaming"
STREAM_SCRIPTS_DIR="$STREAM_ROOT/scripts"
STREAM_PUBLISH_DIR="$STREAM_ROOT/publish"
STREAM_LOG_DIR="/var/log/streaming"
STREAM_RECORD_DIR="/var/lib/streaming/recordings"
SOURCE_REPO_URL=${source_repo_url_json}

echo "Setting up Java S3 bucket services..."

if [ -z "$SOURCE_REPO_URL" ]; then
    echo "SOURCE_REPO_URL is required."
    exit 1
fi

apt-get update
apt-get upgrade -y
apt-get install -y openjdk-17-jdk maven git nginx curl ca-certificates docker.io ffmpeg

mkdir -p "$APP_ROOT" "$PROXY_DIR/publish" "$STREAM_SCRIPTS_DIR" "$STREAM_PUBLISH_DIR" "$STREAM_LOG_DIR" "$STREAM_RECORD_DIR"

if [ ! -d "$SOURCE_DIR/.git" ]; then
    git clone "$SOURCE_REPO_URL" "$SOURCE_DIR"
else
    git -C "$SOURCE_DIR" pull --ff-only
fi

chown -R root:root "$STREAM_ROOT"
chown -R www-data:www-data "$PROXY_DIR" "$STREAM_LOG_DIR"

cd "$SOURCE_DIR"
mvn -f bucket/pom.xml package -DskipTests
# The alerting service is now a standalone top-level app; build it (with its
# reactor dependencies) from the root pom.
mvn -f ../../pom.xml -pl apps/alerting -am package -DskipTests

# The bucket-proxy jar also carries the stream analysis worker as its
# --mode=stream-worker mode (dev.orwell.bucket.proxy.streaming.AnalysisWorker),
# so the executable fat jar doubles as the worker jar.
cp bucket/proxy/target/bucket-proxy-0.1.0-SNAPSHOT-exec.jar "$PROXY_DIR/publish/bucket-proxy.jar"
cp ../alerting/target/alerting-0.1.0-SNAPSHOT-exec.jar "$STREAM_PUBLISH_DIR/alerting.jar"
cp bucket/detection/target/bucket-detection-0.1.0-SNAPSHOT-exec.jar "$STREAM_PUBLISH_DIR/bucket-detection.jar"

cp bucket/proxy/scripts/record_stream.sh "$STREAM_SCRIPTS_DIR/record_stream.sh"
cp bucket/proxy/scripts/analyze_stream.sh "$STREAM_SCRIPTS_DIR/analyze_stream.sh"
chmod 755 "$STREAM_SCRIPTS_DIR/record_stream.sh" "$STREAM_SCRIPTS_DIR/analyze_stream.sh"

cat > /etc/default/streaming <<STREAMENVEOF
STREAM_SOURCE_URL=rtsp://127.0.0.1:8554/live
STREAM_ANALYSIS_SOURCE_URL=rtsp://127.0.0.1:8554/live
STREAM_ANALYSIS_ENDPOINT=${stream_analysis_endpoint_json}
STREAM_ANALYSIS_WORKER_JAR=$PROXY_DIR/publish/bucket-proxy.jar
ALERT_SERVER_HOST=127.0.0.1
ALERT_SERVER_PORT=9000
ALERT_LOG_FILE=$STREAM_LOG_DIR/alerts.log
ALERT_EMAIL_ENABLED=false
ALERT_EMAIL_TO=
ALERT_EMAIL_FROM=
SMTP_HOST=
SMTP_PORT=587
SMTP_USERNAME=
SMTP_PASSWORD=
SMTP_USE_TLS=true
DETECTION_SERVER_HOST=127.0.0.1
DETECTION_SERVER_PORT=9001
DETECTION_ALERT_URL=http://127.0.0.1:9000/alerts
DETECTION_ALERT_COOLDOWN_SECONDS=60
DETECTION_MIN_CONFIDENCE=0.0
STREAM_LOG_DIR=$STREAM_LOG_DIR
STREAM_RECORD_DIR=$STREAM_RECORD_DIR
STREAM_SEGMENT_SECONDS=3600
STREAM_SEGMENT_EXTENSION=mp4
STREAM_SEGMENT_FORMAT=mp4
STREAM_SEGMENT_FORMAT_OPTIONS=movflags=+empty_moov+default_base_moof+frag_keyframe
STREAM_KEYFRAME_INTERVAL=15
STREAM_ANALYSIS_FPS=
STREAM_ANALYSIS_WIDTH=640
STREAM_INPUT_TRANSPORT=tcp
STREAM_INPUT_FORMAT=rtsp
STREAM_RTMP_PORT=1935
STREAM_RTSP_PORT=8554
STREAMENVEOF

cat > /etc/systemd/system/mediamtx.service <<'MEDIAMTXEOF'
[Unit]
Description=MediaMTX ingest server
After=network-online.target docker.service
Wants=network-online.target docker.service

[Service]
Type=simple
Restart=always
RestartSec=10
ExecStart=/usr/bin/docker run --rm --name mediamtx --network host --env-file /etc/default/streaming bluenviron/mediamtx:v1.19.1
ExecStop=/usr/bin/docker stop mediamtx

[Install]
WantedBy=multi-user.target
MEDIAMTXEOF

cat > /etc/systemd/system/stream-alert.service <<'STREAMALERTEOF'
[Unit]
Description=Stream alert server
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
EnvironmentFile=/etc/default/streaming
ExecStart=/usr/bin/java -jar /opt/streaming/publish/alerting.jar
Restart=always
RestartSec=10
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
STREAMALERTEOF

cat > /etc/systemd/system/stream-detection.service <<'STREAMDETECTIONEOF'
[Unit]
Description=Stream detection server
After=network-online.target mediamtx.service stream-alert.service
Wants=network-online.target mediamtx.service stream-alert.service

[Service]
Type=simple
EnvironmentFile=/etc/default/streaming
ExecStart=/usr/bin/java -jar /opt/streaming/publish/bucket-detection.jar
Restart=always
RestartSec=10
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
STREAMDETECTIONEOF

cat > /etc/systemd/system/stream-recorder.service <<'STREAMRECORDEREOF'
[Unit]
Description=Live stream recorder
After=network-online.target mediamtx.service
Wants=network-online.target mediamtx.service

[Service]
Type=simple
EnvironmentFile=/etc/default/streaming
ExecStart=/opt/streaming/scripts/record_stream.sh
Restart=always
RestartSec=10
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
STREAMRECORDEREOF

cat > /etc/systemd/system/stream-analyzer.service <<'STREAMANALYZEREOF'
[Unit]
Description=Live stream analyzer
After=network-online.target mediamtx.service stream-detection.service
Wants=network-online.target mediamtx.service stream-detection.service

[Service]
Type=simple
EnvironmentFile=/etc/default/streaming
ExecStart=/opt/streaming/scripts/analyze_stream.sh
Restart=always
RestartSec=10
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
STREAMANALYZEREOF

cat > /etc/systemd/system/s3-proxy.service <<'SERVICEEOF'
[Unit]
Description=Java S3 Upload Proxy Server
After=network-online.target
Wants=network-online.target

[Service]
User=www-data
WorkingDirectory=/opt/s3-proxy/publish
# Configuration goes through the env schema (JarvisProxyEnvs); the entry point takes no
# program args (it would treat them as an env-loader selector and exit).
ExecStart=/usr/bin/java -jar /opt/s3-proxy/publish/bucket-proxy.jar
Restart=always
RestartSec=10
Environment=JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8
Environment="SERVER_PORT=5000"
Environment="PROXY_S3_BUCKET_NAME=${bucket_name}"
Environment="PROXY_S3_REGION=${aws_region}"
Environment="PROXY_AUTH_SERVER_BASE_URL=${auth_server_base_url_json}"
Environment="AUTH_IDENTITY_PROVISIONING_KEY=${auth_identity_provisioning_key_json}"
Environment="PROXY_MANAGEMENT_USERNAME=${proxy_management_username_json}"
Environment="PROXY_MANAGEMENT_PASSWORD=${proxy_management_password_json}"
Environment="PROXY_MANAGEMENT_SESSION_SECRET=${proxy_management_session_secret_json}"
Environment="PROXY_LOGGING_AUDIT_FILE=/var/log/s3-proxy/audit.log"
Environment="PROXY_CORS_ALLOWED_ORIGINS=${allowed_origins_json}"
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
SERVICEEOF

cat > /etc/nginx/sites-available/s3-proxy <<'NGINXEOF'
server {
    listen 80;
    listen [::]:80;
    server_name _;

    client_max_body_size 5G;

    location / {
        proxy_pass http://localhost:5000;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_request_buffering off;
        proxy_buffering off;
        proxy_read_timeout 600s;
        proxy_send_timeout 600s;
    }
}
NGINXEOF

ln -sf /etc/nginx/sites-available/s3-proxy /etc/nginx/sites-enabled/
rm -f /etc/nginx/sites-enabled/default
nginx -t

docker pull bluenviron/mediamtx:v1.19.1

systemctl daemon-reload
systemctl enable s3-proxy nginx mediamtx stream-alert stream-detection stream-recorder stream-analyzer
systemctl restart s3-proxy nginx
systemctl restart mediamtx stream-alert stream-detection stream-recorder stream-analyzer

echo "Java bucket service setup complete."
