# Keeboarder Ubuntu GNOME Quick Start

## 1. Use GNOME Wayland or Xorg

The default `CAPTURE_BACKEND="auto"` detects the current GNOME session. Wayland uses GNOME Shell/PipeWire; Xorg uses FFmpeg `x11grab`.

## 2. Install Dependencies

```bash
sudo apt update
sudo apt install ffmpeg curl pulseaudio-utils x11-utils gjs
sudo apt install gstreamer1.0-tools gstreamer1.0-pipewire gstreamer1.0-plugins-good
```

## 3. Configure

```bash
cd clients/linux
nano config.sh
```

Set at least:

```bash
UPLOAD_MODE="proxy"
PROXY_URL="https://your-proxy-host"
PROXY_USERNAME="uploader"
PROXY_PASSWORD="change-me"
```

Use an auth-server client identity created from the proxy `/admin` panel for `PROXY_USERNAME` and `PROXY_PASSWORD`.

For direct S3 upload instead, set `UPLOAD_MODE="s3"` and configure `S3_BUCKET`, `AWS_REGION`, and AWS CLI credentials.

Optional audio check:

```bash
pactl list short sources
```

## 4. Install

```bash
chmod +x *.sh
./install.sh
```

## 5. Operate

```bash
./manage.sh status
./manage.sh logs
./manage.sh upload
./manage.sh verify
./manage.sh stop
./manage.sh start
```
