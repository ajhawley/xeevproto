# XEEV TV Prototype (Android)

A native Kotlin + Jetpack Compose Android / Android TV prototype for an authorised XEEV playlist integration. The current milestone implements XEEV device registration, pairing detection, playlist download, parsing of verified Live TV records, and playback of the exact channel URL supplied by XEEV using AndroidX Media3 ExoPlayer.

> Use only with playlists and streams you are authorised to access. XEEV's endpoints are undocumented/private and may change; this project is an experimental client, not an official XEEV application.

## Verified integration flow

1. Create a stable 32-character installation `deviceid` and call `POST https://xeev.app/apk/get/newappid` with form field `deviceid`.
2. Display the returned `xresult.appid` to the user. The returned `xresult.qrcode` image URL is displayed where available.
3. Poll `POST https://xeev.app/apk/get/settings/{appcode}` with `version=2.6.0`, `deviceid`, and `wakeup=0`.
4. Treat the device as paired only when `xresult.lineid` is a valid 32-character alphanumeric playlist ID. The unpaired value `"0"` is not considered paired. `force` is not used as the sole pairing signal because observed paired settings may still return `force: true`.
5. Download `GET https://xeev.app/load/apk/{lineid}`.
6. Parse verified tab-delimited `#CAT` and `#CHAN` records. For each channel, play the supplied field-6 URL exactly as provided; do not rebuild a URL from the provider API's `stream_id`.
7. Use Media3 ExoPlayer to play the channel URL. Channel images, EPG identifiers and catch-up URLs are retained in the parsed model when present.

## Current feature set

- XEEV legacy app-code registration and QR image display.
- Manual pairing checks and optional 20-second automatic polling (up to 10 minutes).
- Automatic playlist download after successful pairing; saved playlist IDs are refreshed at app launch.
- Live TV category navigation, channel search, channel logos and playback through Media3.
- Android phone and Android TV launcher support; D-pad focusable category/channel rows.
- Private values (device ID, app code, playlist ID and playback URLs) are kept in app preferences/memory and are not included in source control.

## Parser scope / known limitations

The parser currently implements `#CAT` and `#CHAN`, whose tab-delimited fields were checked against an actual XEEV playlist response. Unknown record types are counted but not interpreted. Movie and series record layouts, EPG payloads, and catch-up playback have not yet been verified in the downloaded playlist, so they are not exposed as finished UI features in this milestone. The channel's supplied playback URL is used verbatim.

The app does not embed provider credentials, does not construct provider playback paths from Xtream stream IDs, and does not make assumptions about the content host. The playlist response contains private playback URLs and must not be committed, logged, or shared publicly.

## Build

Open the project in Android Studio with Android SDK Platform 35 installed. The GitHub Actions workflow builds `assembleDebug` using JDK 17 and Gradle 8.9, then uploads `app-debug.apk` as the `xeev-tv-prototype-debug-apk` workflow artifact.

Local command-line build (after a Gradle wrapper is generated or installed):

```bash
gradle wrapper --gradle-version 8.9
./gradlew assembleDebug
```

## Important security notes

- Never commit a real device ID, app code, playlist ID, username/password, raw XEEV playlist file, stream URL, or capture containing authentication/session data.
- The playlist may contain per-stream access paths. Treat it as sensitive data.
- TLS verification remains enabled. Do not add certificate bypasses or `curl -k`-style behaviour to the app.
- Use only content you are entitled to access and play.
