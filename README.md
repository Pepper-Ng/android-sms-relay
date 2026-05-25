# ONS Rooster Koppeling (Android)

This Android app is the phone-side companion for the ONS roster backend.

The backend now runs as a long-lived HTTPS service. This app handles the parts that must happen on the phone itself:

- collecting the initial ONS login details from the user
- registering the phone with the backend
- receiving a `listen_sms` push through Firebase Cloud Messaging
- reading the incoming ONS SMS code
- relaying that code back to the backend over HTTPS
- showing a small notification when the backend reports that login has completed

<<<<<<< HEAD
## Relationship to the backend

- Backend repository: <https://github.com/Pepper-Ng/ons-rooster-backend>
- Default public backend URL: `https://onsrooster.stefhermans.nl`

The backend is responsible for:

- storing the ONS credentials securely
- running the Playwright login flow
- requesting the SMS code when ONS triggers 2FA
- completing the login and roster sync
- serving debug information and calendar output

## Current app architecture

The legacy persistent WebSocket service has been removed.

The app now follows a single path:

1. The user enters the backend URL, ONS login URL, username, and password in the app.
2. The app fetches the current FCM token and sends the setup payload to the backend over HTTPS.
3. The backend stores the credentials, starts a login attempt, and issues a backend bearer token for the phone.
4. When ONS asks for SMS-based 2FA, the backend sends an FCM data push with a challenge ID.
5. `OnsFirebaseService` registers a temporary SMS receiver, extracts the code, and sends it to the backend.
6. The backend completes login and sends an `auth_result` push so the phone knows the loop is complete.

## Why the setup starts on Android

The phone is the only place where the SMS code arrives, so it is the most natural place to initiate pairing.

This design has a few practical benefits:

- the user enters the ONS credentials only once in the app
- the backend becomes the always-on remote worker
- the app does not need a permanent socket connection
- the backend can keep reusing the stored credentials for scheduled refreshes

## What the app stores locally

The app stores:

- backend base URL
- ONS login URL
- username
- optional setup code
- backend-issued device bearer token
- FCM token
- last known backend status summary

The app does not keep the ONS password after it has been sent to the backend.

## User interface

The UI now exposes:

- permission checks for SMS and notifications
- battery optimization guidance
- backend base URL field
- ONS login URL field
- username field
- password field
- optional setup code field
- device label field
- save and pair action
- manual backend status refresh action
- FCM token display and copy action

All user-facing text is stored in Dutch string resources.

## Build requirements

- Android Studio with a current Android Gradle Plugin toolchain
- Android SDK for API 34
- Firebase project configured for package name `com.hermans.onssmsrelay`
- `google-services.json` placed in `app/`

## Build and test

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

## First-run flow on the device

1. Install the app.
2. Open it once.
3. Grant the SMS and notification permissions.
4. Disable battery optimization for the app.
5. Leave the default backend URL in place or change it if needed.
6. Enter the ONS login URL, username, and password.
7. Enter the optional setup code only if the backend was configured with one.
8. Tap the save button.

After that, the phone mainly waits for FCM pushes and only wakes up when the backend needs the next 2FA code.

## Firebase behavior

The app uses FCM data messages instead of a permanent foreground service.

That keeps battery usage lower because:

- Android and Firebase maintain the long-lived push connection
- the app only registers the SMS receiver for a short time during an active challenge
- the background work ends again as soon as the code is relayed

## Tests included

The local JVM tests currently cover:

- OTP extraction from SMS text
- backend URL normalization
- backend status payload parsing

Run them with:

```bash
./gradlew testDebugUnitTest
```

## Current limitation

The backend-side login and challenge loop is implemented, but the real ONS post-login scraping still depends on live page inspection and selector tuning. The app is already aligned with the intended HTTPS plus FCM architecture.
