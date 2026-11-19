# XEEV / X3M Protocol Notes and Android Test Harness

A small Android/Kotlin test harness for understanding XEEV/X3M app-code pairing and playlist delivery, with the goal of integrating the protocol into existing IPTV applications.

**This is not intended to become a full IPTV player.** The Android app exists to exercise endpoints, inspect real responses, verify parsing and playback URL behaviour, and turn those observations into a reusable integration reference.

> Use only playlists and streams you are authorised to access. These endpoints are undocumented and may change. This is an independent research/integration project, not an official XEEV application.

## Project goals

1. Reproduce registration and pairing.
2. Download both supported playlist representations.
3. Document field mappings from real responses, not guesses.
4. Preserve the exact stream URL supplied by the playlist.
5. Download and parse XMLTV when the M3U advertises a guide.

The XEEV custom playlist and standard M3U are two playlist formats available after the same pairing flow.

## Protocol at a glance

| Purpose | Method | Endpoint |
|---|---|---|
| Register an app/device code | POST | `https://xeev.app/apk/get/newappid` |
| Check pairing / retrieve playlist ID | POST | `https://xeev.app/apk/get/settings/{APP_CODE}` |
| Download XEEV custom playlist | GET | `https://xeev.app/load/apk/{PLAYLIST_ID}` |
| Download standard M3U playlist | GET | `https://xtream-editor.com/load/m3u/{PLAYLIST_ID}` |
| Download XMLTV EPG | GET | URL advertised by the M3U `url-tvg` attribute |

These are observed working endpoints. No Xtream username/password is required by the client for the registration/pairing/download steps described here.

## 1. Device registration

**Request**

```http
POST https://xeev.app/apk/get/newappid
Content-Type: application/x-www-form-urlencoded
Accept: application/json
```

Form field:

| Field | Meaning |
|---|---|
| `deviceid` | Stable 32-character device/install identifier |

The test app creates a UUID, removes hyphens, uppercases it, and saves it in Android SharedPreferences. Reuse the same device ID for later pairing checks from that installation.

**Successful response shape**

```json
{
  "xresult": {
    "appid": "APP_CODE",
    "server": "https://xeev.app",
    "type": "playlist",
    "qrcode": "https://xeev.app/tmp/APP_CODE.png"
  }
}
```

Observed fields:

- `xresult.appid`: short app code displayed to the user.
- `xresult.server`: server/base URL returned by XEEV.
- `xresult.type`: the legacy registration route returned `playlist`.
- `xresult.qrcode`: QR image URL, when supplied.

The legacy route is the working route for this integration. An attempted V3 route, `POST /e2/get/newappidV3`, returned `type: "line"`; the generated code was not accepted by XEEV Playlist Manager in testing. Do not substitute the V3 route without new evidence.

## 2. Pairing check

After the user enters the app code in XEEV Playlist Manager and associates a playlist, check:

```http
POST https://xeev.app/apk/get/settings/{APP_CODE}
Content-Type: application/x-www-form-urlencoded
Accept: application/json
```

Form fields:

| Field | Observed value / meaning |
|---|---|
| `version` | `2.6.0` in the tested client |
| `deviceid` | Same stable ID used during registration |
| `wakeup` | `0` in the tested client |

The response contains `xresult.lineid`.

**Pairing rule:** treat `lineid` as the playlist ID only when it is exactly 32 alphanumeric characters:

```regex
^[A-Za-z0-9]{32}$
```

Before pairing, the observed value was `"0"`. After pairing, it was a 32-character alphanumeric string. Do not assume the ID is hexadecimal; a tested ID contained letters outside A-F.

Do not use `force` as the only pairing indicator. Its value was not a reliable standalone signal in observed responses. The validated `lineid` is the useful signal.

The app currently checks manually and can optionally poll every 20 seconds for up to 10 minutes. Those are test-app choices, not confirmed server requirements.

## 3. Playlist download options

Once `lineid` is obtained, the same paired playlist ID can be used with either format.

### XEEV custom format

```http
GET https://xeev.app/load/apk/{PLAYLIST_ID}
Accept: text/plain
```

Observed response: a large tab-delimited text playlist containing records such as `#PL`, `#CAT` and `#CHAN`.

### Standard M3U format

```http
GET https://xtream-editor.com/load/m3u/{PLAYLIST_ID}
Accept: application/vnd.apple.mpegurl, text/plain
```

Observed response begins with `#EXTM3U` and contains `#EXTINF` metadata lines followed by stream URLs.

Both endpoints have been exercised successfully using the playlist ID returned by the same pairing check. There is no separate M3U registration/pairing procedure in the tested flow.

## 4. XEEV tab-delimited playlist

The XEEV response is tab-delimited. Do not parse it by splitting on arbitrary spaces: channel names can contain spaces and punctuation.

### Verified record types

| Record | Observed purpose | Parser status |
|---|---|---|
| `#PL` | Playlist/header metadata | Recognised; not needed for live channel display |
| `#CAT` | Category | Parsed |
| `#CHAN` | Live channel | Parsed |
| Other record types | Not fully identified | Counted or ignored |

### `#CAT` fields

| Zero-based index | Meaning |
|---:|---|
| 0 | Record type: `#CAT` |
| 1 | Category ID |
| 2 | Category name |

### `#CHAN` fields

This mapping was checked against a real downloaded record using tab-aware field inspection:

| Zero-based index | Meaning | Notes |
|---:|---|---|
| 0 | Record type: `#CHAN` | |
| 1 | Record ID | XEEV record identifier |
| 2 | Category ID | Joins to `#CAT` |
| 3 | Channel name | |
| 4 | EPG ID field | May be empty or `0` in observed data |
| 5 | Secondary/provider EPG field | Example: `BBC1` |
| 6 | Logo URL | Image URL |
| 7 | Playback URL | **Use this exact value** |
| 8 | Archive flag | |
| 9 | Catch-up URL | May be empty |
| 10 | Provider stream ID | Metadata only; not a playback URL |
| 11+ | Additional fields | Not yet fully documented |

A verified sample had the logo URL at index 6 and actual stream URL at index 7. An earlier field-offset assumption was wrong and was corrected after inspecting the tab-separated fields.

**Important:** do not construct a playback URL from the provider stream ID or guessed Xtream username/password components. The URL in field 7 is the one that successfully returned MPEG-TS in testing.

The exact semantics of fields after index 10, and the layouts of VOD/series records, remain unverified.

## 5. Standard M3U playlist

A real M3U sample had this structure (private values redacted):

```m3u
#EXTM3U url-tvg="https://xtream-editor.com/load/epg/{PLAYLIST_ID}"
#EXTINF:-1 tvg-chno="144" tvg-id="BBC1" tvg-name="UK FHD BBC ONE" tvg-logo="https://example.invalid/logo.png" group-title="UK | ENTERTAINMENT",UK FHD BBC ONE
https://stream-host.example/path/to/stream
```

### Observed attribute mapping

| M3U field | Meaning | App mapping |
|---|---|---|
| `url-tvg` | EPG URL in the `#EXTM3U` header | `ParsedPlaylist.epgUrl` |
| `tvg-chno` | Channel number | `TvChannel.channelNumber` |
| `tvg-id` | EPG channel identifier | `TvChannel.epgChannelId` |
| `tvg-name` | Canonical channel name | Preferred channel display name |
| `tvg-logo` | Channel logo URL | `TvChannel.logoUrl` |
| `group-title` | Category/group name | Playlist category |
| URL after `#EXTINF` | Playback URL | `TvChannel.playbackUrl` |

The M3U parser reads `url-tvg` first and `x-tvg-url` as a fallback. It handles quoted attribute values and resolves relative stream/logo/EPG URLs against the playlist URL where applicable.

Each `#EXTINF` entry is associated with the next non-comment line, which is treated as its stream URL.

## 6. XMLTV EPG

The M3U header supplies the EPG address. A real sample advertised:

```text
https://xtream-editor.com/load/epg/{PLAYLIST_ID}
```

When opened by the user, this endpoint delivered a gzip-compressed file named `guide.xml.gz`. The uploaded sample was inspected and confirmed to contain XMLTV data.

### Download and decompression

The Android client:

1. GETs the exact URL supplied by the M3U.
2. Accepts XML/text responses.
3. Checks response bytes for gzip magic bytes (`1F 8B`).
4. If gzip-compressed, decompresses the body.
5. Decodes the result as UTF-8.
6. Passes the XML text to the XMLTV SAX parser.

Do not assume the URL path itself ends in `.gz`; the observed M3U URL did not, while the server delivered a gzip file/redirect named `guide.xml.gz`.

### XMLTV structure

The supplied file contained standard XMLTV elements:

- `<tv>` root.
- `<channel id="...">` entries with display names.
- `<programme channel="..." start="..." stop="...">` entries.
- `<title>` and optional `<desc>` elements.

XMLTV timestamps were in the common `yyyyMMddHHmmss +ZZZZ` form.

### EPG matching policy

Keep matching deliberately simple: **exact ID matching only**, after trimming and case normalisation. If an M3U `tvg-id` does not exactly match an XMLTV channel ID, show no programme information for that channel.

Do not add fuzzy matching, aliases or name-based matching unless a future integration explicitly asks for it.

A real playlist contained multiple BBC One variants. One entry used `BBC1`, while other entries used `BBC1.uk`; the guide contained `BBC1.uk`. This explains why some variants can show guide data while another does not. It is acceptable for unmatched variants to show no EPG.

### Android XML parser note

Some Android SAX implementations reject optional parser configuration features such as XInclude awareness or secure-processing. The current parser attempts optional security features independently and tolerates unsupported features. It also installs an `EntityResolver` that returns an empty source to prevent external DTD/entity retrieval.

EPG is ancillary: if EPG parsing fails, playlist/channel loading and playback should continue.

## 7. Playback URL handling

For a live channel:

1. Read the playback URL directly from the playlist.
2. Pass it to the media player without rebuilding or normalising the path.
3. Do not replace it with a URL derived from the provider stream ID.
4. Keep it private; the path may contain access credentials/tokens.

A tested URL from the XEEV custom playlist returned HTTP 200 with `video/mp2t` content and MPEG-TS data. The same URL was also present in the standard M3U sample.

A guessed provider-style URL constructed from the provider host/stream ID returned HTTP 500 during testing. This is further reason to use the supplied playback URL verbatim.

The Android test app uses AndroidX Media3 ExoPlayer and explicitly declares MPEG-TS for the tested live streams.

## 8. Error handling and state

| Situation | Recommended behaviour |
|---|---|
| Registration request fails | Show registration/network error; allow retry |
| Pairing `lineid` is `0` or invalid | Remain unpaired |
| Playlist request fails | Preserve pairing details and allow refresh/retry |
| Unknown playlist record type | Ignore safely; do not fail the whole playlist |
| Malformed channel row | Skip that row and count/report it |
| EPG URL absent | Continue without EPG |
| EPG download/decompression/XML parse fails | Continue with live channels; report EPG unavailable |
| EPG ID has no exact match | Show no programme information |
| Stream playback fails | Report player error; do not rewrite the stream URL |

The playlist and EPG are independent of successful playback: a guide failure must not prevent channels from loading.

## Android implementation notes

| File | Responsibility |
|---|---|
| `data/XeevRepository.kt` | Registration, pairing, playlist/EPG HTTP requests and gzip decompression |
| `data/XeevPlaylistParser.kt` | XEEV `#CAT` / `#CHAN` parsing and channel models |
| `data/M3uPlaylistParser.kt` | M3U attributes, channel/category extraction and EPG URL |
| `data/XmlTvParser.kt` | XMLTV programme parsing |
| `MainActivity.kt` | Test UI, pairing flow, channel list and Media3 playback |

The device ID, app code, playlist ID and selected playlist format are stored in app preferences. The app currently declares both standard launcher and Android TV launcher categories.

## Known unknowns

Do not treat these as implemented or verified protocol behaviour:

- VOD/movie and series record formats in the XEEV custom playlist.
- Exact semantics of every `#CHAN` field beyond the inspected live-channel fields.
- Catch-up/archive request flow and playback behaviour.
- Undocumented server-side pairing expiry or device limits.
- Whether all XEEV accounts/servers return identical record layouts.
- Whether every EPG response is gzip-compressed; detect gzip from response bytes.
- Long-term stability or official support status of these endpoints.

## Security and privacy

Never commit or publish:

- A real `deviceid`.
- App codes or QR images containing a live app code.
- Playlist IDs.
- Raw downloaded XEEV/M3U playlists.
- XMLTV files if they contain account-specific/private data.
- Full playback URLs, provider credentials or session tokens.
- Screenshots showing any of the above.

Use redacted placeholders in documentation and issue reports. Keep TLS certificate verification enabled. Do not add certificate bypasses. Only use playlists and streams you are authorised to access.

## Build

Open the project in Android Studio with Android SDK Platform 35 installed. The app uses JDK 17 and Gradle 8.9 in the GitHub Actions build.

Local command-line build (after a Gradle wrapper is generated or installed):

```bash
gradle wrapper --gradle-version 8.9
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

The GitHub Actions workflow builds `assembleDebug` and uploads the debug APK as a workflow artifact.

## Project direction

The protocol investigation is the primary deliverable. Keep the Android UI as a compact test harness; do not grow it into a feature-rich IPTV player.

After this README is agreed, the next UI pass should focus on:

- Locking the app to landscape on phones/tablets.
- Making the pairing/login screen more compact so controls and QR fit comfortably.
- Reducing excessive typography, spacing and fixed-width assumptions.
- Making the channel/category/player layout adapt to available landscape width.
- Keeping Android TV/D-pad use functional where practical.

The UI should make protocol testing easy, not become the project itself.
