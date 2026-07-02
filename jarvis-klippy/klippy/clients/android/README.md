# Clippy Android

Kotlin Android client for posting text clipboard entries to the Clippy server.

## Behavior

- Paste reads the current Android clipboard while the app is open.
- Send posts the text to `POST /clipboard`.
- Auto sync checks the clipboard every two seconds while the screen is open.
- Android share sheet support accepts `text/plain` shares into Clippy.

Android does not allow a normal third-party app to silently monitor clipboard changes in the background like a desktop client. Keep the app open for polling, or share text into Clippy from another app.

## Build

Open `clients/android` in Android Studio and run the `app` configuration.

Start PostgreSQL, the auth server, and the app server from the repository root before sending clipboard entries:

Start the auth database on port `5433` and the app database on port `5432` using your preferred local PostgreSQL setup, then run the auth server and app server.

Create a client identity and login with the auth server:

```bash
curl -i http://localhost:8081/identities \
  -H 'Content-Type: application/json' \
  -d '{"clientId":"android-pixel-8","secret":"change-me-please"}'

curl -s http://localhost:8081/login \
  -H 'Content-Type: application/json' \
  -d '{"clientId":"android-pixel-8","secret":"change-me-please"}'
```

Enter the returned token in the app's Client Token field.

The app allows cleartext HTTP so it can talk to a local development server:

```text
http://192.168.1.10:8080
```

Use your computer's LAN IP address from a physical Android device. `localhost` on the phone points at the phone itself, not the server machine.

For an emulator, use:

```text
http://10.0.2.2:8080
```

## Server contract

The Android app sends the same payload as the mac client:

```http
POST /clipboard
Authorization: Bearer <client-token>
Content-Type: application/json
```

```json
{
  "clientId": "android-pixel-8",
  "content": "clipboard text",
  "timestamp": "2026-06-23T12:00:00Z"
}
```
