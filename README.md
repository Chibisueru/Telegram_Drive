# Telegram Drive

An Android cloud-drive app (Material 3, Jetpack Compose) that stores your files in a private
channel in your own Telegram account, so storage costs nothing.

- **Folders**: create, rename, move and delete folders and files, nested as deep as you like,
  with breadcrumbs, search, sorting and a list or grid view.
- **View in the app, no download needed**: video and audio stream straight from Telegram and
  you can seek anywhere. Images open with pinch-zoom, PDFs and text files render in the app.
  Anything else can be opened in another app or shared.
- **Up to 2 GB per file** (4 GB with Telegram Premium). Each file is one Telegram message.
- **Works on all your phones**: sign in on another phone and your drive and folders come back.
- **Background transfers**: uploads and downloads run one after another in a queue that keeps
  going with the screen off. The notification shows two bars: files done and the current file.
  The **Transfers** tab lists everything, with cancel and retry. Optional **Wi-Fi only** mode.
- **App lock**: optional PIN, with fingerprint/face unlock. The PIN always works as a fallback.
- **Share to the app**: "Share → Telegram Drive" from any app uploads files into a folder you pick.
- **Select several items** (long-press) to download, move, favorite or delete them together.
- **Trash**: deleted items can be restored for 30 days (with an instant Undo).
- **Camera backup**: new photos and videos from DCIM/Camera upload automatically, optionally
  only on Wi-Fi and while charging.
- **Favorites and Recent** shortcuts at the top of My Drive.
- Five views (list, compact list, grid, large grid, gallery) and six sort orders.
- Material You dynamic color, light/dark theme, edge-to-edge, predictive back.
- Requires **Android 16 (API 36) or newer**.

## Setup (once)

1. Tap **Open my.telegram.org** in the app, log in there with your phone number, open
   **API development tools** and create an app (any title and short name).
2. Copy **App api_id** and **App api_hash** into the app, add your phone number and tap
   **Log in**. Enter the code Telegram sends you (and your two-step verification password,
   if you use one).
3. The app creates a private channel called "Telegram Drive" in your account. Your files go
   there.

On another phone, do the same: the app finds your existing "Telegram Drive" channel and
restores your folders.

## Build

```bash
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug         # install on a connected phone
```

You need JDK 17+ and the Android SDK with platform 37 (`local.properties` → `sdk.dir`).

## How it works

The app is a Telegram client for one purpose. It talks to Telegram directly over MTProto
through [TDLib](https://github.com/tdlib/td), Telegram's official client library, using
[tdl-coroutines](https://github.com/g000sha256/tdl-coroutines). There is no server in between.

| Piece | Where |
|---|---|
| Login and the saved session | `telegram/Telegram.kt` |
| Uploads, downloads, thumbnails | `telegram/TgFiles.kt` |
| Finding or creating the drive channel | `telegram/DriveProvisioner.kt` |
| ExoPlayer `DataSource` that plays while TDLib downloads | `player/TelegramDataSource.kt` |
| Transfer queue (Room) and the foreground service that works through it | `transfer/` |
| PIN lock and biometrics | `security/` |
| Camera backup (WorkManager job watching MediaStore) | `backup/CameraBackup.kt` |
| Local index (Room): folders and which message holds which file | `data/db/` |
| Index backup to the channel | `data/IndexSync.kt` |
| Screens | `ui/setup`, `ui/browser`, `ui/transfers`, `ui/viewer`, `ui/settings`, `ui/security` |

**Files** are document messages in the drive channel, with a thumbnail for photos and videos.

**Folders** don't exist in Telegram, so the folder tree lives in a local database. After every
change (debounced) the whole index goes up to the channel as `tgdrive_index.json`, and the
channel's description is updated to point at it (`#tgdrive index:<message id>`). A new phone
reads the description, downloads that message and rebuilds the folders.

**Streaming**: when you play a video, TDLib starts downloading from the byte the player asks
for. The player reads bytes as soon as they're on disk. Seeking restarts the download at the
new position.

This design follows [caamer20/Telegram-Drive](https://github.com/caamer20/Telegram-Drive),
which also stores files through the user's own account. Unlike that app, this one keeps one
channel and supports nested folders.

## Limits and caveats

- **The PIN protects the app's screens, not its files.** It stops someone with your unlocked
  phone from opening the drive. It doesn't encrypt the Telegram session stored by the app.
- **Your Telegram session is stored on the phone** (app-private, excluded from backups) so you
  stay signed in. Anyone who can extract it could use your Telegram account. Use a phone lock
  screen. You can end the session at any time in Telegram → Settings → Devices, or with
  *Settings → Sign out* in the app.
- **Privacy**: the channel is private, but files are not end-to-end encrypted, so Telegram's
  servers can read them. Don't store anything you wouldn't trust to a normal cloud provider.
- **Uploads need free space**: Android only hands out the file's content, not a path, so each
  file is copied into the app's cache before upload and deleted afterwards.
- Don't post or delete messages in the storage channel by hand, and don't edit its description.
- Editing on two phones at once isn't merged: the last phone to save its index wins. Use
  *Settings → Restore from Telegram* to pull the latest copy.
- This app isn't affiliated with Telegram. Telegram can rate-limit or restrict accounts that
  move very large amounts of data.
