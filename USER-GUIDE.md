# DSH Pocket — user guide

A single Android app that runs **DeepSeek Harness (DSH)** entirely on your phone — no PC, no
Termux app, no root. Everything (Linux userland, Node.js, DSH, your projects) lives inside the
app's private storage.

APK: `dsh-pocket/dist/DSH-Pocket.apk`
- 329,553,536 bytes (314.3 MiB)
- sha256 `a9f76313d76e1c9d405667e749996cbface0cd11e324f02eef47ff45923d4967`
  (also in `dist/DSH-Pocket.apk.sha256`)

Package `com.dshpocket` · min Android 8.0 (arm64) · targetSdk 28 (deliberate; see "Why" below).
Verified on Android 16 / arm64-v8a; other phones need the same ABI (the launcher shows
`Android <version> · <abi> · supported` and refuses to start on non-arm64 devices).

---

## 1. Install (one time)

**Option A — from the phone**
1. Copy `DSH-Pocket.apk` to the phone (USB, Google Drive, Telegram to yourself…).
2. Tap it in Files → allow *Install unknown apps* for your file manager → Install.
   Samsung may warn "built for an older version of Android" — that is expected and required
   (the old target grants the legacy permission the embedded Linux needs).

**Option B — from the PC (faster, no prompts)**
```
adb install -r dsh-pocket/dist/DSH-Pocket.apk
```

## 2. First launch (one time, ~1–2 minutes)

1. Open **DSH Pocket**. Tap the single "Start" card.
2. Allow the storage permission when asked (needed so the AI can read/write your projects in
   Downloads/Documents via `/sdcard`).
3. The app unpacks its built-in Linux environment (~1.1 GB / 72k files, measured 14.5 s on the
   S22 Ultra) and boots DSH. Status goes **Preparing → Starting server → Ready** (measured ~48 s
   from launch to UI on this phone), then the DSH UI opens. Keep the app open until it says Ready.
4. In the DSH UI choose **Sign in** (DeepSeek account) or **Add API Key**, and the agent is ready.

Later launches take only a few seconds (the runtime is already installed; only DSH restarts).

## 3. Working on a project

- DSH's default workspace lives inside the app's private Linux filesystem and survives app
  restarts/updates: open the folder icon in the DSH sidebar to add or switch a workspace.
- The phone's shared storage is mounted at **`/sdcard`** inside the app's Linux environment, so
  you can point a DSH workspace at e.g. `/sdcard/Download/myproject` (visible to other apps)
  or keep it private.
- Ask the agent to create/refactor/run things. The Linux side has `git`, `curl`, `unzip`, `xz`,
  `less`, `nano` and a normal `apt` (IPv4 forced for apt is documented in the runtime notes);
  the agent can `apt-get install` more packages if you need them.

## 4. Keep it alive

- While DSH is running you get an ongoing notification with **Stop** / **Open** actions — don't
  swipe it away unless you're done.
- On Samsung, set *Battery → Background usage limits* → DSH Pocket = **Unrestricted** so
  One UI doesn't freeze the server when the screen is off. The app holds a partial wake lock
  while it runs.
- The app is a foreground service; it restarts the server automatically once if it dies.

## 5. Troubleshooting

- **Stuck on Preparing / Starting** → open the in-app log (launcher → View log). The last lines
  usually say what's wrong ("proot error", "no DSH_READY"). "Restart server" in the menu retries.
- **Banner "Unable to create default workspace"** → fixed in the shipped image; if you see it,
  pick a workspace folder in the UI once.
- **UI didn't open** → menu → *Open DSH UI*, or *Open in Chrome* (same URL/token).
- **Out of space** → the runtime needs ~1.3 GB app data + ~2.2 GB free to unpack. Free up space
  and retry; the installer only marks the install complete after a full unpack.

## 6. Uninstall

Uninstalling removes the app plus ~1.3 GB of runtime/workspace data. Projects kept under
`/sdcard` are **not** deleted.

---

## What's inside (technical)

| Piece | Version | Notes |
|---|---|---|
| App | DSH Pocket 1.0 | Plain Java, no AndroidX, no Gradle build; WebView UI |
| Linux userland | Ubuntu 24.04.5 arm64 | glibc; runs under **PRoot** (no root) |
| Node.js | v22.23.4 | official linux-arm64 |
| DSH | `@deepseek-ai/dsh@0.2.1-rc.2` | preinstalled; `koffi`/`node-pty` use arm64 prebuilts (no compile) |
| PRoot | 5.1.107.100 | Termux build + `loader`, `libtalloc`, `libandroid-shmem` |
| WebView | system Chromium | loads `http://127.0.0.1:<random>/?token=…` printed by `dsh web` |

Why not Termux: running DSH natively in Termux fails because `koffi` cannot compile against
Android bionic. A glibc userland under PRoot gives the same arm64 Linux experience with the
native addons' prebuilt binaries. Why targetSdk 28: Android 10+ blocks `execve()` of binaries
from app data for newer target SDKs; target 28 keeps the legacy policy (the phone's Termux uses
the same trick). Only visible effect: Play-Protect-style "older Android" warnings.

**No network traffic from the app besides what DSH itself does** (DeepSeek API, and package
installs if you ask for them). Everything else is local.

Licenses: DSH is MIT; PRoot is GPLv2+ (binary + loader from the Termux project); Ubuntu base and
the bundled packages keep their own licenses (see `/usr/share/doc` inside the app's runtime).

## Phone-first UI (v1.11)

DSH's web UI is desktop-first, so the app injects a phone shell around it:
- **Bottom navigation**: *Chats* (session drawer), *New* (new session), *More* (account/sign-in/settings, reload, Chrome, restart/stop). Search tab removed by request.
- **Drawers instead of columns**: the session sidebar and the right panel (Workspace files / terminal) open as full-height overlays with a dimmed background; the chat uses the full 384px width.
- **Thumb-sized targets**: 40-48px buttons, sticky composer with a short placeholder, keyboard hides the nav bar and keeps the composer above the IME.
- **Dialogs**: sign-in/API-key/settings are resized for phones (stacked settings tabs, sticky close button); model/workspace/account menus open as bottom sheets.
- **Reading comfort**: message text 15.5px with tighter gutters; code blocks scroll horizontally; message actions are 40px.
- Long-press/back behaviour: Back first closes a drawer/dialog, then navigates the UI, then returns to the launcher.


