# Install DSH Pocket on other phones (shareable APK)

DSH Pocket is a **single self-contained APK** — no PC, no Termux, no root, no Play Store.
You can copy the APK to any compatible phone and install it from a file manager.

## Compatibility

| Requirement | Why |
|---|---|
| **Android 8.0+ (API 26)** | minSdk of the app |
| **64-bit ARM phone (`arm64-v8a`)** | the embedded Linux runtime (PRoot + Ubuntu arm64 + Node.js arm64) is aarch64 only. Every mainstream phone since ~2018 is arm64; x86/x86_64 tablets, Chromebooks and emulators are **not** supported |
| **~2.3 GB free storage** | 314 MB APK + ~1.2 GB extracted rootfs + a little workspace |
| Internet | DeepSeek API / sign-in; the runtime itself runs offline |

The app shows the device's Android version and ABI on the launcher subtitle
(e.g. `Android 16 · arm64-v8a · supported`). On a non-arm64 device it still installs
but stops with a clear "needs a 64-bit ARM phone" message instead of failing silently.

## Sharing the APK

* `dist/DSH-Pocket.apk` — **314 MB**, sha256 in `dist/DSH-Pocket.apk.sha256`.
* Transfer by USB, Google Drive, Telegram, etc. (WhatsApp caps files at 100 MB and
  will refuse it.) For teammates, a GitHub Release asset is the cleanest channel: <https://github.com/mhzsajan/dsh-pocket/releases> (make the repo public first if you want others to download it).

## Install from the phone

1. Copy `DSH-Pocket.apk` to the phone.
2. Open it from Files/Downloads.
3. Allow **Install unknown apps** for the file manager when prompted.
4. Play Protect may say *"unsafe app blocked"* or *"built for an older Android
   version"* — choose **More details → Install anyway** / **OK**. This is expected:
   the APK is debug-signed and targets SDK 28 on purpose (that is what lets the
   embedded Linux execute its own binaries from app storage).
5. First launch: allow storage permission, then wait ~1–2 minutes while the
   runtime unpacks (progress bar + log are visible). The DSH web UI opens by itself.

## Install from a PC (optional)

```bash
adb install -r DSH-Pocket.apk
adb shell am start -n com.dshpocket/.MainActivity
```

## First run on a new phone

1. Tap **Choose workspace → Default workspace** (one time per install).
2. Authenticate: **Get started → Add API Key** (paste a DeepSeek API key — simplest)
   or **More → Account, sign in & settings → Sign in** (opens the DeepSeek sign-in
   flow; copy the link to Chrome if the in-app page does not open).
3. Start a session and ask for something. Files live in the app's private workspace;
   the phone's storage is available inside the app at `/sdcard`.

## Updates

Every build is signed with the committed debug key (`keystore/debug.keystore`,
password `android`), so newer APKs install **over** the old one and keep your data.
If someone builds with a different key, Android refuses the update — uninstall first
(that deletes the app's ~1.3 GB of runtime/session data; projects under `/sdcard`
survive).

## Uninstall

Uninstalling removes the app, its Linux runtime and DSH sessions (~1.3 GB).
Files you copied into `/sdcard` stay on the phone.

## Troubleshooting

| Symptom | Fix |
|---|---|
| `INSTALL_FAILED_...` / "App not installed" | Free storage, allow unknown sources, and uninstall any older copy signed with a different key |
| Stuck on "Starting server" | Open the in-app log (launcher → Runtime log). `proot error:` lines mean missing loader/lib/rootfs — tap **Restart** (or reinstall) |
| "needs a 64-bit ARM phone" | The phone is x86/arm32 — not supported by this build |
| UI feels frozen / taps do nothing | Tap **Restart** in the launcher; if it persists, **Copy log** and report it |
| Battery kills the runtime | Launcher → **Battery** (exempt from Doze) and set the app to *Unrestricted* in One UI battery settings |

