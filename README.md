# ONS SMS Relay (Android)

ONS SMS Relay is the Android companion app for the ONS rooster synchronization stack.

The broader goal of these projects is to automate access to a personal Nedap ONS account so the roster can be synchronized to agenda items. The backend handles the browser automation and calendar generation. This Android app handles the part that the backend cannot do on its own: receive the SMS-based 2FA code on the phone, extract it, and relay it back so the backend can complete the login flow.

## Companion backend

- GitHub repository: <https://github.com/Pepper-Ng/ons-rooster-backend>

The backend is responsible for:

- starting the Nedap ONS login flow
- detecting when 2FA is required
- triggering the Android app to listen for the SMS code
- receiving the relayed code
- completing login and continuing the roster sync
- publishing the resulting agenda or calendar output

## Why this app exists

Nedap ONS does not provide the needed export or synchronization path for the roster in the form required by the backend project. The backend can automate login and scraping, but the 2FA code arrives on the phone that is registered for SMS authentication. That makes the phone part of the login chain.

This app exists to close that gap with as little manual work as possible:

- the phone keeps receiving the real 2FA SMS
- the backend asks the phone to start listening only when needed
- the app extracts the numeric code and sends it back automatically
- the backend can then finish login and continue generating calendar data

## Current state of the Android app

The Android project currently contains two background strategies.

### 1. Persistent foreground service path

This is the older approach.

- `RelayService` keeps a WebSocket connection open to the backend.
- `BootReceiver` starts that foreground service after reboot.
- `SmsReceiver` is declared in the manifest and receives incoming SMS broadcasts.
- `SmsReceiver` only forwards messages when `RelayService` has switched the app into listening mode.

Why this approach exists:

- it is straightforward to reason about
- it gives the backend a continuously available connection to the phone
- it survives restarts via `BOOT_COMPLETED`

Tradeoff:

- higher battery cost
- more background activity
- more friction on modern Android, especially on aggressive vendors such as Samsung

### 2. FCM-triggered wake-up path

This is the newer and intended direction.

- `OnsFirebaseService` receives an FCM data message, typically `listen_sms`.
- Android wakes the app when the push arrives.
- the service temporarily registers an SMS receiver in code
- the app waits for a limited time for the incoming message
- the code is extracted and sent back over HTTP
- the temporary listener is removed again

Why this approach was added:

- lower idle battery usage
- lets Android manage the long-lived push connection instead of this app
- better fit for modern background execution limits
- no need to keep a permanent app-managed socket open if push delivery is reliable enough

Tradeoff:

- it depends on Firebase setup and push delivery
- it adds a second architecture path while the project is still transitioning

### Important note about the current repository state

Both paths are still present and registered in the app today. That means the codebase is currently in a hybrid or transition state rather than a fully cleaned-up final architecture.

The FCM path appears to be the preferred direction, but the persistent WebSocket path still exists and may still be useful during testing or if the backend has not yet fully switched to push-driven triggering.

## High-level flow

1. The backend starts a Nedap ONS login flow.
2. ONS requests SMS-based 2FA.
3. The backend asks the Android app to begin listening.
4. The phone receives the Nedap ONS SMS.
5. The app extracts the 2FA code, usually a 4 to 8 digit number.
6. The app relays the code back to the backend.
7. The backend completes login and continues the roster synchronization flow.

## Design choices and rationale

### Android app instead of server-only automation

Reason:
The SMS arrives on the device, not on the backend host. If the goal is to automate the sync without manually copying codes each time, the phone must participate in the workflow.

### SMS parsing with numeric extraction and full-text fallback

Reason:
Most OTP messages are simple numeric codes. Extracting the first 4 to 8 digit sequence keeps the Android side simple. If no code is found, the full SMS body can still be forwarded so the backend can handle unusual formats.

### Foreground service plus boot receiver

Reason:
This was a practical first step for reliability. A foreground service is explicit, visible to the user, and resilient enough for an always-on connection model.

### FCM wake-up model

Reason:
This reduces battery cost and background pressure by moving the always-on connection responsibility to the Android OS and Firebase infrastructure.

### Minimal user interface

The UI is intentionally small. It currently focuses on:

- requesting SMS permission
- requesting notification permission on Android 13+
- guiding the user to disable battery optimization
- showing or copying the FCM token

Reason:
The app is meant to be a small background companion, not a fully interactive client.

### Cleartext traffic currently allowed

The manifest currently allows cleartext traffic.

Reason:
The current implementation assumes a trusted local or private network for development or home-lab style deployment, where the backend may be addressed over plain HTTP or plain WebSocket.

Tradeoff:

- simple local bring-up
- not ideal for untrusted networks

If this is ever used beyond a trusted local environment, the backend path should move to HTTPS or WSS, or be placed behind a private tunnel such as Tailscale or WireGuard.

### Hardcoded backend addresses

The current code contains placeholder server addresses in source code.

Reason:
This keeps the initial implementation simple.

Tradeoff:

- fast to bootstrap
- not convenient for real deployment
- not ideal for switching between environments

The next logical improvement is to move server configuration into the app UI and store it in persistent settings.

## What the app currently does

- requests and checks SMS permissions
- requests notification permission where required
- shows the FCM token for pairing with the backend
- helps the user disable battery optimization
- starts a foreground relay service on boot for the persistent path
- supports a newer FCM-triggered path for on-demand listening
- extracts 4 to 8 digit numeric codes from incoming SMS messages
- retries relaying the result to the backend on temporary network failures

## What the app does not fully do yet

- it does not yet expose backend host configuration in the UI
- it still contains both the legacy persistent path and the newer FCM path
- it still relies on hardcoded placeholder backend URLs in code
- it assumes the backend and Firebase configuration are prepared separately

## Build and setup

### Requirements

- a current Android Studio installation
- the Android SDK for API 34
- a Firebase project for this package name: `nl.landvanhorne.smsrelay`
- `google-services.json` placed in `app/`
- a reachable backend instance from the phone

### Build steps

1. Open the project in Android Studio.
2. Place `google-services.json` in `app/`.
3. Update the backend URLs in source if needed:
   - `OnsFirebaseService.SERVER_CALLBACK_URL`
   - `RelayService.SERVER_URL`
4. Sync Gradle.
5. Build and install the app.

You can also build from the command line:

```bash
./gradlew assembleDebug
```

### First run on the device

1. Open the app once.
2. Grant SMS permission.
3. Grant notification permission if prompted.
4. Disable battery optimization for the app.
5. Copy or register the FCM token if you are using the FCM flow.

On Samsung devices in particular, battery optimization settings matter. If the device aggressively suspends the app, background behavior can become unreliable.

## Integration notes with the backend

The backend and Android app are two halves of one workflow.

Backend side:

- triggers login refresh
- determines when the 2FA challenge appears
- triggers the phone to begin listening
- waits for the relayed code
- completes the login flow and continues roster synchronization

Android side:

- receives the trigger through WebSocket or FCM
- listens for the incoming SMS only when needed
- extracts the code from the SMS
- sends the result back to the backend

For backend-specific setup details, see:

- <https://github.com/Pepper-Ng/ons-rooster-backend>
- `C:/Users/Stef/Documents/Projects/onsrooster/ons-backend/README.md`

## Security and operational notes

- This project should only be used with an account and phone you control.
- SMS permissions are sensitive and should be granted intentionally.
- The relay endpoints should not be exposed publicly without authentication and transport security.
- If the backend remains on a local network, prefer a private network path or VPN instead of exposing raw HTTP or WebSocket endpoints.
- Firebase credentials and `google-services.json` should be managed separately from public source control unless that is an explicit project decision.

## Suggested next improvements

- add a settings screen for backend URL, expected sender, timeout, and protocol choice
- decide on one architecture and remove the other once verified
- move sensitive configuration out of source code
- add clearer status reporting for active listening, last relay result, and backend reachability

## Changelog

### 2026-05-24

- updated the project to build with a current Android Studio and Gradle toolchain
- added a repository-level `.gitignore` and cleaned generated files from version control
- added this README to document the Android app and its relationship to the backend

### Earlier project work

- created the Android relay app structure and UI
- added SMS permission handling and battery optimization guidance
- added a foreground service plus boot receiver path using WebSocket
- added Firebase Cloud Messaging support for wake-on-demand triggering
- added SMS code extraction and relay logic using OkHttp and coroutines
