# Truck Capture (GoPro 360 POC)

Android app for capturing a 360 photo of a truck with a GoPro Max and uploading
it to S3. A tech scans the truck's QR code, the app fires the GoPro shutter over
its Wi-Fi, pulls the photo down, and queues a background upload that survives
app close and reboot.

- `applicationId`: `net.thompsoncs.truckcapture`
- minSdk 26, targetSdk 35
- Deployed internally by MDM — not published to Play

## First-time setup

You need **two** things beyond a normal clone.

### 1. `local.properties`

Points Gradle at your Android SDK. Gitignored, so create it:

```properties
sdk.dir=/Users/<you>/Library/Android/sdk
```

Opening the project in Android Studio once will also write this for you.

### 2. Brand assets — **required, or the build fails**

The licensed Geometr415 fonts and the TCS wordmark are gitignored here; they're
tracked in exactly one place, the [`tcs-brand`](https://github.com/thompsoncs/tcs-brand)
repo. `res/` references them by name, so **a fresh clone will not compile until
you sync them**:

```bash
./scripts/sync-brand.sh                      # finds a tcs-brand checkout automatically
./scripts/sync-brand.sh /path/to/tcs-brand   # or point it at one
```

The palette itself is mirrored by hand in `app/src/main/res/values/colors.xml`
from `brand.css`'s `:root`. If the brand colors change, update that file too —
the script only copies fonts and the logo.

## Building

```bash
./gradlew :app:assembleDebug      # app/build/outputs/apk/debug/
./gradlew :app:assembleRelease    # app/build/outputs/apk/release/  ← deploy this
```

Don't run a terminal build while Android Studio is doing a Gradle sync. Both
write `app/build`, and a collision has produced duplicate `"... 2.xml"` files in
the intermediates that make `parseDebugLocalResources` fail. If that happens,
`rm -rf app/build` and rebuild.

## Signing

Both build types are signed with the TCS-owned key when it is available.

**The keystore and its password are not in this repo.** This repo is public, and
a signing key in it would let anyone build an APK that Android accepts as a
genuine update to `net.thompsoncs.truckcapture` and sideload it over the real
one onto a tablet.

### Why a shared key exists at all

Before it, every developer's Android Studio signed with its own generated
`~/.android/debug.keystore`. An APK built on one machine could not update an
install from another, and MDM refused it:

> Silent Installation Failed. App signature mismatch with an existing version.

A single key that the team shares — distributed out of band, not through git —
is what makes any machine able to build a deployable update.

### Setting it up

1. Get `tcs-truckcapture.jks` and its password from whoever holds the TCS
   signing key. Put the file somewhere outside version control; `keystore/` in
   this repo is gitignored and is the expected spot.
2. `cp keystore.properties.example keystore.properties` and fill in the
   passwords. That file is gitignored.

CI can skip the file and set `TCS_KEYSTORE_FILE`, `TCS_KEYSTORE_PASSWORD`,
`TCS_KEY_ALIAS` and `TCS_KEY_PASSWORD` instead.

With neither configured, debug builds still work (signed with your local
Android Studio debug key) but `assembleRelease` produces an **unsigned APK that
cannot be deployed**. The build prints a warning saying so.

**Do not lose the keystore.** Without it no installed copy can ever be updated
again — every tablet would need a manual uninstall and reinstall.

R8 / minification is deliberately **off**. The AWS SDK and ML Kit both resolve
classes reflectively, and the keep rules needed to shrink them safely haven't
been worked out. Revisit before any real release.

## Architecture

Single activity (`MainActivity`) hosting a Navigation graph of five fragments:

```
Home ──> Scan ─────┐
  │                ├──> Capture ──> Review ──┐
  └──> ManualEntry ┘        ▲                │
  │                         └────────────────┘  (another angle, same truck)
  └──> Queue
```

| Package    | What's in it                                                            |
|------------|-------------------------------------------------------------------------|
| `capture/` | `CaptureEngine` (GoPro sequence, no UI), `CaptureViewModel`, state models |
| `ui/`      | The five fragments plus `TcsFragment` (shared ViewModel + inset helpers)  |
| root       | `S3Uploader`, `UploadQueue`, `UploadWorker`, `NetworkUtils`               |

The capture runs in `CaptureViewModel`, **not** in a fragment. That's what lets
one truck session span Capture → Review → Capture for several angles without
rescanning, and it means a rotation mid-download no longer kills the capture.

### GoPro specifics

Hard-coded to the camera's AP at `10.5.5.9`, so the phone must be joined to the
GoPro's own Wi-Fi. `CaptureEngine` carries field-earned resilience worth keeping:

- A **90s read timeout** — right after a shot the camera may still be stitching
  the 360 frame and the transfer trickles. 20s killed slow-but-working downloads.
- A **20-attempt media-list poll** waiting for the new `GS*.JPG` to appear.
- **Re-acquire on `EPERM`** — Android (notably Samsung) tears down the GoPro
  Wi-Fi to return to mobile data, invalidating the `Network` handle mid-transfer.
  The download retry re-acquires the network and rebuilds the client.

Photos are written twice: app-private `files/pending/{truck}/` for the uploader
to consume, and `Documents/TruckCapture/{truck}/` as the user's own copy, kept
whether or not the upload ever succeeds.

### Upload

`UploadWorker` (WorkManager, unique work per photo, exponential backoff) calls
`S3Uploader`, which authenticates via a Cognito identity pool — no AWS secret
ships in the app. Because each photo is its own work request, one stuck transfer
never blocks the others.

Config lives in `S3Uploader`: bucket `tcs-gopro-images`, `us-east-1`.

### QR format

Expected: `guid | truckno | othernumbers`. The GUID becomes the upload filename
(`{guid}_{yyyyMMdd_HHmmss}.JPG`) and the truck number becomes the folder.
Manual entry produces no GUID, so those photos fall back to the camera's own
filename. The raw scanned value is printed to the diagnostics log — check there
first if a scan files a photo under the wrong truck.

## Diagnostics

The Uploads screen carries an on-screen log, deliberately, because a tech on a
truck lot has no access to logcat. Everything also goes to logcat under the
`TRUCKCAP` tag:

```bash
adb logcat -s TRUCKCAP:D
```
