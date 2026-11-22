# SelamCall — Real-Time Audio & Video Calling Setup Guide

## Architecture Overview
- **UI**: Kotlin + Jetpack Compose + Material 3 (`SelamCallTheme` with Dark/Light/System persistence via DataStore)
- **Authentication**: Google Sign-In via Jetpack Credential Manager (`GetSignInWithGoogleOption`) + Firebase Auth
- **Database & Signaling**: Cloud Firestore (custom named database configured via `R.string.firestore_database_id` in `app/src/main/res/values/firebase_applet_config.xml`)
- **Real-Time Media**: Peer-to-peer WebRTC (`io.getstream:stream-webrtc-android:1.3.10`) using Google STUN servers:
  - `stun:stun.l.google.com:19302`
  - `stun:stun1.l.google.com:19302`

## Firestore Collections & Rules
All collections are protected by zero-trust security rules in `firestore.rules` and documented in `firebase-blueprint.json`:
1. `/users/{userId}` — Private owner-only user profile (`uid`, `email`, `username`, `countryCode`, `localPhone`, `phoneNumber`, `displayName`, `onlineStatus`, `discoverable`, `lastSeen`, `createdAt`, `updatedAt`).
2. `/directory/{userId}` — Authenticated searchable directory (`discoverable == true`) with live presence indicators.
3. `/usernames/{username}` — Unique lowercase `@username` reservation claims.
4. `/phone_claims/{phoneKey}` — Unique E.164 phone number reservation claims.
5. `/calls/{callId}` — WebRTC SDP Offer/Answer signaling, live video state, and call history (`callerId`, `calleeId`, `participantUids`, `callType`, `status`, `durationSeconds`).
6. `/calls/{callId}/callerCandidates/{candidateId}` & `/calls/{callId}/calleeCandidates/{candidateId}` — ICE candidate exchange subcollections.

## Testing Security Rules Locally
```bash
FIRESTORE_EMULATOR_HOST="127.0.0.1:8085" node --test firestore.test.js
```

## Developer Support
- **Telegram**: `@chalamohammed3` (`https://t.me/chalamohammed3`)
- **WhatsApp**: `0983156394` (`https://wa.me/251983156394`)
