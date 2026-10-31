# DropLink

Offline, peer-to-peer file sharing for **any** Android phone (Android 8 → 17), including phones
without Google Play services: Xiaomi/HyperOS China ROM, Huawei/Honor (HMS), OPPO/ColorOS,
vivo/OriginOS, realme, OnePlus China, Tecno/Infinix/itel.

- No internet, no account, no ads, no analytics, no telemetry.
- Zero Google Play services / Firebase / ML Kit dependencies (enforced by the build).
- **USB cable mode** (Android Open Accessory): two phones joined by a USB-C cable reach 10+ MB/s even when
  one has slow 2.4 GHz-only Wi-Fi (e.g. Galaxy A12). The cable carries the same encrypted protocol.
- Fastest path first, picked automatically: **direct phone-to-phone network (Wi-Fi Direct, 5 GHz) → shared Wi-Fi router → QR code**.
  The receiver runs its own Wi-Fi Direct network with a fixed name/password; the sender joins it like
  normal Wi-Fi (one-time Android "Connect?" prompt), so data never goes through a router.
- TLS 1.2+/1.3 with per-install keys, certificate pinning, SHA-256 per file, resume after drops.
- One universal APK (pure Kotlin/Java, no native code).

> **Status:** the code is complete through all 7 phases. The protocol, TLS, resume and
> checksum logic are covered by JVM tests that pass, and the whole app compiles against
> API 37. **It has not yet been run on real phones**: please fill in the test matrix below.

---

## Contents

1. [Features](#features)
2. [Architecture](#architecture)
3. [Wire protocol](#wire-protocol)
4. [Security model](#security-model)
5. [Permissions](#permissions-and-why)
6. [Build](#build)
7. [CI (GitHub Actions)](#ci-github-actions)
8. [Using it](#using-it)
9. [OEM setup tips](#oem-setup-tips)
10. [Testing](#testing)
11. [Known limitations](#known-limitations)
12. [Roadmap](#roadmap-non-goals-for-v1)

---

## Features

| Area | What's there |
|---|---|
| Send | Photo Picker (images + videos), documents (SAF), whole folders (relative paths kept), installed apps (base APK), Android share sheet (`SEND` / `SEND_MULTIPLE`) |
| Receive | Visible device name, LAN + Wi-Fi Direct advertising, QR hotspot toggle, accept/decline dialog **and** notification actions, trusted devices with optional auto-accept (off by default) |
| Transfer | Per-file progress, speed, ETA, overall progress, cancel from either phone, resume from the last confirmed byte, SHA-256 verified per file (failures clearly marked) |
| Background | Foreground service (`dataSync`), partial WakeLock + low-latency/high-perf WifiLock, keeps going with the screen off |
| History | Room database: sent/received items with open, share, delete; **speed log** with measured MB/s per transfer |
| Settings | Device name, save location (Download/DropLink via MediaStore, or any SAF folder incl. SD card), trusted devices, connection mode, theme (system/light/dark), language (English / বাংলা) |
| Integration | Quick Settings tile toggles Receive mode, notification Accept/Decline/Cancel/Stop |
| Reliability | "Phone setup" screen: detects the brand, battery-optimization exemption, OEM autostart screens, lock-in-recents tip |
| Self-test | Settings → *Run self-test*: a full 64 MB TLS transfer to itself, proves the (R8-minified) build works and measures the phone's speed ceiling |

## Architecture

```
┌─────────────────────────────── UI (Jetpack Compose, Material 3) ───────────────────────────────┐
│ Home · Send · Receive · Transfer · History · Settings · Phone setup · Accept dialog           │
│ ViewModels (MVVM) ── PermissionFlow (rationale → system prompt) ── ZXing scanner (QR)          │
└──────────────┬──────────────────────────────────────────────┬──────────────────────────────────┘
               │ StateFlow                                    │ commands
┌──────────────▼──────────────────────────────────────────────▼──────────────────────────────────┐
│ AppContainer (manual DI, one instance of everything)                                           │
│   SendManager ── ModeSelector: LAN → Wi-Fi Direct (15 s ×2) → QR · reconnect + resume loop     │
│   ReceiveManager ── accept/decline · trusted devices · Wi-Fi Direct group · QR hotspot          │
│   TransferTracker (single source of truth for progress) · HistoryRecorder (Room)               │
│   TransferService (foreground, dataSync, locks, notification) · ReceiveTileService             │
└──────────────┬──────────────────────────────────────────────┬──────────────────────────────────┘
               │                                              │
┌──────────────▼───────────── net/ ─────────────┐   ┌─────────▼────────── storage/ ─────────────┐
│ LanDiscovery     NsdManager "_droplink._tcp"   │   │ Sinks: MediaStore (10+) · SAF tree ·      │
│ WifiDirectLink   WifiP2pManager + DNS-SD       │   │        public Download (8–9)              │
│ LocalHotspot     startLocalOnlyHotspot (recv)  │   │ Sources: Photo Picker / SAF / folders /   │
│ HotspotJoiner    WifiNetworkSpecifier (10+),   │   │          installed-app APKs               │
│                  WifiManager.addNetwork (8–9)  │   └───────────────────────────────────────────┘
└──────────────┬────────────────────────────────┘
               │ java.net.Socket (bound to the right Network)
┌──────────────▼───────────── core/ (pure Kotlin, runs on a plain JVM) ──────────────────────────┐
│ security/  IdentityStore (EC P-256 + self-signed cert, BouncyCastle) · PinningTrustManager ·   │
│            PairingCode (4 digits from both fingerprints)                                       │
│ protocol/  FrameWriter/Reader · Json · Hello/Accept · SafePath · ResumePlanner/LedgerStore ·   │
│            Checksums · QrPayload · ModeSelector · SpeedMeter                                   │
│ transfer/  SenderSession · ReceiverServer (TLS server, resume takeover) · CancelToken          │
└────────────────────────────────────────────────────────────────────────────────────────────────┘
```

Source layout: `app/src/main/java/app/droplink/{core,net,storage,transfer,service,data,oem,ui,util,di}`.

**Why the `core/` split:** everything that decides whether a transfer is correct (framing, TLS,
pinning, resume offsets, checksums, path sanitizing) has no Android imports, so it is tested on the
JVM in milliseconds, including real TLS transfers over `127.0.0.1`.

## Wire protocol

TCP, wrapped in TLS. Every message is a frame:

```
┌──────────┬──────────────────────┬─────────────────────────┐
│ type: u8 │ length: u32 (BE)     │ payload (length bytes)  │
└──────────┴──────────────────────┴─────────────────────────┘
DATA payload ≤ 1 MiB (raw bytes); control payloads are UTF-8 JSON ≤ 8 MiB.
```

| # | Frame | Direction | Payload |
|---|---|---|---|
| 1 | `HELLO` | S → R | `{v, transferId, senderName, deviceId, mode, resume, files:[{path,size,mime}]}`, always the first frame |
| 2 | `ACCEPT` | R → S | `{offsets:[…], done:[…]}`: where each file restarts, which are already complete |
| 3 | `DECLINE` | R → S | `{reason}` |
| 4 | `FILE_START` | S → R | `{index, offset}` |
| 5 | `DATA` | S → R | file bytes |
| 6 | `FILE_END` | S → R | `{index, sha256}` |
| 7 | `FILE_ACK` | R → S | `{index, ok, bytes, reason}` |
| 8 | `PROGRESS` | R → S | `{index, bytes}`: bytes **fsynced** to storage (the "confirmed offset") |
| 9 | `CANCEL` | both | `{reason}` |
| 10 | `DONE` | both | transfer finished (echoed) |
| 11 | `ERROR` | both | `{message}` |
| 12 | `PING` | both | keep-alive while one side re-hashes a resumed prefix |

**Resume:** the receiver fsyncs every 8 MiB or 1 s, records the offset in a small JSON *ledger*
(`files/resume/<transferId>.json`, written atomically) and reports it with `PROGRESS`. After a drop,
the sender reconnects (up to 10 times, back-off 1→8 s) with `resume: true` and the same
`transferId`. The receiver checks that the transfer id, sender key and file list match, then
answers with `min(confirmed, bytes actually on disk)` per file. Both sides rebuild the SHA-256
state by re-reading the prefix they already have, so the final hash covers the whole file. A
reconnect takes over a half-dead old connection instead of being rejected as "busy". Ledgers
older than 7 days are pruned with their partial files.

**Paths:** `SafePath` rejects absolute paths, `..`, and FAT-illegal characters, so a malicious
sender can't write outside the destination folder.

## Security model

- Each install generates an **EC P-256 key + self-signed certificate** (BouncyCastle), stored in
  app-private storage (PKCS#12). Backup is disabled so keys never leave the phone.
- Both sides present certificates (mutual TLS, TLS 1.3 where available, else 1.2).
- **QR mode:** the QR code carries the receiver's certificate SHA-256; the sender pins it. A
  different certificate aborts the handshake ("security check failed").
- **LAN / Wi-Fi Direct:** both screens show a **4-digit code** derived from *both* fingerprints
  (`SHA-256("droplink-pair-v1|fpA|fpB")`, order-independent). A man-in-the-middle must present his
  own certificate to each side, so the codes differ. The receiver compares before accepting.
- Trusted devices are stored as `(deviceId, certificate fingerprint)`; auto-accept requires both
  to match and is off by default.
- No internet calls: `INTERNET` is only used for local sockets; cleartext is disabled; no
  analytics SDKs; the GMS-ban check blocks Google services from sneaking in transitively.

## Permissions and why

| Permission | API levels | Why |
|---|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE`, `CHANGE_NETWORK_STATE` | all | Local TCP sockets; request/bind the hotspot network. Nothing goes to the internet. |
| `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE` | all | Wi-Fi Direct, local-only hotspot, joining it, mDNS on ROMs that filter multicast |
| `NEARBY_WIFI_DEVICES` (`neverForLocation`) | 33+ | Wi-Fi Direct discovery/group and the local-only hotspot |
| `ACCESS_FINE_LOCATION` + `ACCESS_COARSE_LOCATION` (`maxSdkVersion=32`) | 26–32 | Android requires them for the same Wi-Fi APIs; location services must also be switched on. Never used to read location. |
| `CAMERA` | all | Scanning the receiver's QR code (ZXing, on-device) |
| `POST_NOTIFICATIONS` | 33+ | Progress + Cancel, incoming-file Accept/Decline when the app is closed |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `WAKE_LOCK` | all / 34+ | Keep transfers alive with the screen off |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | all | One-tap exemption on the Phone setup screen |
| `WRITE_EXTERNAL_STORAGE` (`maxSdkVersion=28`) | 26–28 | Save to public Download/DropLink before scoped storage |

Not requested: `MANAGE_EXTERNAL_STORAGE`, `READ_MEDIA_*` (the Photo Picker and SAF need no storage
permission), `QUERY_ALL_PACKAGES` (a `<queries>` launcher filter is enough to list apps).
Every runtime request is preceded by a rationale dialog; refusing Wi-Fi permissions still leaves
same-Wi-Fi (LAN) mode working.

## Build

Requirements: **JDK 17+**, Android SDK with *Platform 37* (`platforms;android-37.0`). The Gradle
wrapper downloads Gradle 9.6.0; AGP is 9.5.0, Kotlin 2.4.21 (built-in Kotlin), KSP 2.3.11.

```bash
./gradlew verifyNoGoogleServices   # GMS/Firebase/ML Kit ban check (also runs before every build)
./gradlew testDebugUnitTest         # JVM unit tests incl. TLS loopback transfers
./gradlew assembleDebug             # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease           # signed + R8-minified app-release.apk (needs a keystore)
./gradlew connectedDebugAndroidTest # on-device loopback test (phone connected via adb)
```

### Release keystore (once)

```bash
keytool -genkeypair -v -keystore droplink-release.jks -alias droplink \
  -keyalg RSA -keysize 4096 -validity 10000 -storetype PKCS12
cp keystore.properties.example keystore.properties   # then fill in the passwords
```

`keystore.properties` and `*.jks` are git-ignored. Instead of the file you can export
`DROPLINK_KEYSTORE_PATH`, `DROPLINK_KEYSTORE_PASSWORD`, `DROPLINK_KEY_ALIAS`,
`DROPLINK_KEY_PASSWORD`. **Back up the keystore**: updates must be signed with the same key.

Without a keystore, `assembleRelease` produces `app-release-unsigned.apk`, which Android won't
install; use the debug APK or set up signing.

### Verifying the R8 keep rules

Install the release APK and run **Settings → Run self-test**. It performs a full TLS transfer
inside the minified build (BouncyCastle cert generation, Conscrypt handshake, framing, hashing,
storage). If a keep rule were missing, it would fail there.

## CI (GitHub Actions)

`.github/workflows/build.yml` runs on pushes, pull requests, `v*` tags and manually:

1. JDK 17 + Android SDK (platform 37, build-tools 36).
2. Decodes the keystore from secrets (if present).
3. `verifyNoGoogleServices` + `testDebugUnitTest`.
4. `assembleRelease` (signed), **or `assembleDebug` if signing secrets are missing**.
5. Uploads the APK (+ `SHA256SUMS.txt`) as the `DropLink-apk` workflow artifact.
6. On a `v*` tag, attaches the APK to a GitHub Release.

Add these repository secrets (*Settings → Secrets and variables → Actions*):

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | `base64 -w0 droplink-release.jks` (macOS: `base64 -i droplink-release.jks`) |
| `KEYSTORE_PASSWORD` | store password |
| `KEY_ALIAS` | `droplink` |
| `KEY_PASSWORD` | key password |

Release: `git tag v1.0.0 && git push origin v1.0.0`.

## Using it

1. **Receiver:** *Receive*. It shows up on the same Wi-Fi and via Wi-Fi Direct. With no Wi-Fi
   network the **QR code** is switched on automatically (toggle it any time).
2. **Sender:** *Send* → pick photos/files/folder/apps → tap the receiver in the list, or
   *Scan receiver's QR code*.
3. Compare the 4-digit code on both phones, accept on the receiver.
4. Files land in **Download/DropLink** (subfolders preserved) or your chosen folder.

Automatic mode: LAN if both phones see each other on the network; otherwise Wi-Fi Direct (15 s,
2 attempts); otherwise the app asks for a QR scan. A specific mode can be forced from the Send
screen's chip or in Settings.

## OEM setup tips

The in-app *Phone setup* screen detects the brand and opens the right screens. Summary:

| Brand | Steps |
|---|---|
| Xiaomi / Redmi / POCO (MIUI, HyperOS) | Security → Permissions → **Autostart** on; App info → Battery saver → **No restrictions**; lock in recents |
| Huawei / Honor | Phone Manager → **App launch** → DropLink → manual: auto-launch, secondary launch, run in background |
| OPPO / realme / OnePlus (ColorOS) | App info → Battery usage → allow background + auto launch; lock in recents |
| vivo / iQOO (OriginOS/Funtouch) | i Manager → **Autostart**; Battery → Background power consumption → allow |
| Samsung | Battery → Background usage limits → not in *Sleeping* / *Deep sleeping* apps |
| Tecno / Infinix / itel | Phone Master → **Auto-start** → on; lock in recents |

On several Chinese ROMs the *first* Wi-Fi Direct connection pops a system "invitation" dialog on
the receiver: it must be accepted. If Wi-Fi Direct is flaky on a phone, QR mode is the reliable path.

## Testing

**Automated**

| Suite | Where | Covers |
|---|---|---|
| `ProtocolTest.kt` | `app/src/test` | frame encode/decode + limits, JSON, header parsing + validation, path traversal, checksums, resume planner + ledger store, QR payload, mode auto-selection, speed/ETA |
| `LoopbackTransferTest.kt` | `app/src/test` | real TLS transfers over 127.0.0.1: folders + empty files, QR pinning (right and wrong key), decline, checksum mismatch detection, **drop mid-file → resume at confirmed offset into the same file**, cancel from receiver, cancel from sender |
| `LoopbackInstrumentedTest.kt` | `app/src/androidTest` | same-device loopback with Conscrypt + BouncyCastle; MediaStore Downloads sink on 10+ |

**Manual test matrix**: please fill in (speed = average from the in-app speed log):

| Brand | Model | Android | ROM | Mode | Result | Speed (MB/s) | Notes |
|---|---|---|---|---|---|---|---|
| | | | | LAN | | | |
| | | | | Wi-Fi Direct | | | |
| | | | | QR hotspot | | | |
| | | | | QR, 1 GB, screen off | | | |
| | | | | Resume after Wi-Fi toggle | | | |

**Acceptance checklist**

- [ ] 1 GB file, both phones with Wi-Fi network and SIM data off, QR mode, checksum OK (green "checksum OK" row)
- [ ] Turn the screen off and background the app during that transfer: it completes
- [ ] `./gradlew verifyNoGoogleServices` passes
- [ ] Installs and runs on Android 8 and on the newest phone available
- [ ] Speed log shows ≥ 10 MB/s on two 5 GHz-capable phones (LAN or Wi-Fi Direct)
- [ ] Deny every permission: app still opens, LAN mode still works, no crash; disable Wi-Fi Direct → falls back to QR

## Known limitations

- **Not yet field-tested** on physical devices; the OEM component names for autostart screens
  come from public ROM knowledge and change between ROM versions (the app falls back to the
  system app-info page).
- The local-only hotspot's band is chosen by Android; many phones pick 2.4 GHz for it, so QR mode
  is often slower (≈ 3–8 MB/s) than LAN/Wi-Fi Direct on 5 GHz.
- "Share installed app" exports the base APK; apps installed as split APKs (App Bundles) need
  their splits too and may not install from the base alone.
- If Android kills the process mid-transfer, the partial file and ledger are kept; restarting
  receive and sending the same selection again resumes only within the same app session (the
  sender's transfer id lives in memory).
- Android 15+ limits `dataSync` foreground services to 6 h per day; the service stops cleanly then.
- Wi-Fi Direct and the QR hotspot share the radio on many phones, so the receiver runs one at a time.

## Roadmap (non-goals for v1)

- iOS and desktop (Windows/macOS/Linux) clients speaking the same protocol
- Optional internet relay for phones that aren't nearby
- Interop with Google Quick Share / Nearby Share
- Persisting the sender's queue so resume survives an app restart
- Multiple simultaneous incoming transfers
