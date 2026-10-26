# DSH Pocket — architecture & working agreement

Goal: ONE sideloadable Android APK that runs DeepSeek Harness (dsh) entirely on the phone,
no PC connection, no Termux app required. Package: `com.dshpocket`. App label: "DSH Pocket".

## Why this design (facts established 2026-10-24)

- Test device: Samsung Galaxy S22 Ultra (SM-S908E), Android 16 (API 36), arm64-v8a,
  4 KB pages. Termux 0.118 (targetSdk 28) installed and working.
- **Termux-native dsh is impossible**: `npm i -g @deepseek-ai/dsh` fails compiling `koffi`
  against Android bionic (`statx` errors). DSH needs a glibc Linux userland.
- Therefore the APK embeds a **glibc Linux userland (Ubuntu/Debian arm64) driven by PRoot**
  (same trick UserLAnd/Andronix/OpenMinis use), plus official Node.js arm64 (glibc) and a
  pre-installed `@deepseek-ai/dsh` npm tree. PRoot = userspace chroot/ptrace, no root needed.
- App targets **targetSdk 28** on purpose: Android grants legacy-app domain which may `execve`
  binaries living in the app data dir (Termux proves this on this exact device). If that ever
  fails, fallback = ship proot + Termux ELF loaders as `jniLibs` (see notes/).

## Runtime layout (inside app `filesDir`)

```
files/
  proot                  # executable proot binary (Termux proot deb, bionic-linked)
  rootfs/                # Ubuntu arm64 rootfs, extracted from assets/rootfs.tar.gz
    usr/local/bin/node   # bundled Node.js linux-arm64
    usr/local/lib/node_modules/@deepseek-ai/dsh   # preinstalled DSH
    root/start-dsh.sh    # guest entrypoint: starts dsh web, prints DSH_READY line
    workspace/           # project workspace (may be bind-mounted to /sdcard later)
  payload/               # start scripts, termux.properties-like configs
  logs/                  # app-visible logs
```

Launch command (app service):

```
<files>/proot -0 -r <files>/rootfs \
  -b /dev -b /proc -b /sys -b /dev/urandom:/dev/urandom \
  -w /root \
  -env HOME=/root -env TERM=xterm-256color -env PATH=/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin \
  /usr/bin/env -i HOME=/root ... /bin/bash /root/start-dsh.sh
```

Guest script contract: `root/start-dsh.sh` must exec `dsh web` and print exactly one line to
stdout: `DSH_READY http://127.0.0.1:<port>/?token=<token>` (or whatever URL dsh prints with token).
The Android service parses that line and hands the URL to the WebView.

## Build pipeline (NO Gradle / NO AndroidX)

Plain Java + SDK build-tools:
`$ANDROID_HOME\build-tools\<version>\{aapt2,d8,apksigner,zipalign}.exe`,
platform `$ANDROID_HOME\platforms\android-35\android.jar`.
Script: `dsh-pocket/build.ps1`. Steps: aapt2 compile+link -> javac -> d8 -> zip assets ->
zipalign -> apksigner (debug keystore in `dsh-pocket/build/`).

## Team working agreement

- Shared phone via adb. Keep test dirs disjoint:
  - runtime experiments: `/data/local/tmp/dshp-runtime/`
  - app installs/tests: package `com.dshpocket`
- Downloads/caches live under `work/` (npm cache = `work/.npm-cache`, gradle not needed).
- Do not run `adb uninstall com.termux` or modify Termux.
- Write scopes: runtime teammate -> `dsh-pocket/runtime/`, `dsh-pocket/assets/`; app teammate ->
  `dsh-pocket/app/`, `dsh-pocket/build.ps1`; notes -> `dsh-pocket/notes/`.
- All paths are relative to the repo checkout root.
