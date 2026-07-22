# Klippy Android

Kotlin Android client for posting text clipboard entries to the Klippy server.

## Behavior

- Paste reads the current Android clipboard while the app is open.
- Send posts the text to `POST /clipboard`.
- Auto sync checks the clipboard every two seconds while the screen is open.
- Android share sheet support accepts `text/plain` shares into Klippy.

Android does not allow a normal third-party app to silently monitor clipboard changes in the background like a desktop client. Keep the app open for polling, or share text into Klippy from another app.

## Build

Open `apps/klippy/clients/android` in Android Studio and run the `app`
configuration.

Start PostgreSQL, the auth server, and the app server before sending clipboard
entries. Both the `auth` and `klippy` databases live in the shared PostgreSQL instance on `5432`
when you use the local defaults.

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
