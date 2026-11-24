# Telegram Drive

An Android cloud-drive app (Material 3, Jetpack Compose) that stores your files in a private
Telegram channel through your own bot, so storage costs nothing and has no size cap.

- **Folders**: create, rename, move and delete folders and files, with breadcrumbs, search,
  sorting and a list or grid view.
- **View in the app, no download needed**: video and audio stream directly from Telegram (seek
  anywhere). Images open with pinch-zoom, PDFs and text files render in the app. Anything else
  can be opened in another app or shared.
- **Any file size**: files are split into 19 MiB parts behind the scenes.
- **Survives reinstalls**: the folder index is pinned in the channel. Connect on a new phone and
  the drive comes back.
- Material You dynamic color, light/dark theme, edge-to-edge, predictive back.
- Requires **Android 16 (API 36) or newer**.

## Setup (once)

1. In Telegram, open **@BotFather**, send `/newbot` and copy the **token**.
2. Create a **private channel**. This is where your files go.
3. Add the bot to the channel as an **administrator** with *Post messages*, *Edit messages* and
   *Delete messages* rights.
4. Post any message in the channel.
5. Open the app, paste the token, tap **Detect** (it fills in the channel ID), then **Connect**.

## Build

```bash
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug         # install on a connected phone
```

You need JDK 17+ and the Android SDK with platform 37 (`local.properties` → `sdk.dir`).

## How it works

| Piece | Where |
|---|---|
| Bot API client (flood control, retries, multipart upload) | `telegram/BotApi.kt` |
| Downloads, byte-range streams, thumbnails | `telegram/TelegramFiles.kt` |
| ExoPlayer `DataSource` that stitches parts into one stream | `player/TelegramDataSource.kt` |
| Uploads/downloads as foreground WorkManager jobs | `work/UploadWorker.kt`, `work/DownloadWorker.kt` |
| Local index (Room): folders, files, parts | `data/db/` |
| Index backup: JSON pinned in the channel | `data/IndexSync.kt` |
| Screens | `ui/setup`, `ui/browser`, `ui/viewer`, `ui/settings` |

**Why chunks?** The Bot API accepts uploads up to 50 MB but only lets a bot download files
up to 20 MB. Every file is cut into 19 MiB parts, each posted as a document. To play a video,
the app finds the part that holds the byte you seek to and streams from there with an HTTP
Range request.

**Why a pinned index?** Telegram has no folders, so the folder tree and the list of parts
are kept in a local database. After every change (debounced) the whole index goes up to the
channel as `tgdrive_index.json` and gets pinned. The previous copy is deleted.

## Limits and caveats

- **Privacy**: a private channel is only visible to its members, but files are not
  end-to-end encrypted, so Telegram's servers can read them. Don't store anything you
  wouldn't trust to a normal cloud provider.
- **Your bot token gives full access** to the drive. It's stored in app-private storage and
  left out of Android backups.
- Telegram limits bots to roughly 20 posts per minute per chat. Uploads run one at a time,
  and the app waits whenever Telegram says to slow down, so large uploads can pause briefly.
- When you delete a file, the app also tries to delete its messages in the channel. Telegram
  may refuse for messages older than 48 hours. Those stay in the channel, but they no longer
  appear in the app.
- Don't post or delete messages in the storage channel by hand, and keep the index message
  pinned.
- Editing on two devices at once isn't merged: the last device to save its index wins. Use
  *Settings → Restore from Telegram* to pull the latest copy.
