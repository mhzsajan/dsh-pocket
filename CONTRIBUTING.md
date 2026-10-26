# Contributing

Thanks for helping with DSH Pocket. This is a small, pragmatic codebase — the whole
app is plain Java (no Gradle, no AndroidX) plus a CSS/JS shell injected into the DSH
web UI, and a hand-written PowerShell build script.

## Prerequisites

* Windows 10/11 with PowerShell 7 (`pwsh`) for `build.ps1`
* JDK 21 (`JAVA_HOME` set)
* Android SDK with `build-tools` (aapt2/d8/zipalign/apksigner) and `platforms/android-35`
* Python 3 (only for the icon/probe generators and `runtime/fetch-assets.ps1`)
* `adb` + an **arm64 Android 8+** phone for runtime testing

## Quick start

```powershell
pwsh -File runtime/fetch-assets.ps1        # third-party runtime binaries (small)
# runtime/rootfs.tar.gz: build once on arm64, see runtime/README.md
pwsh -File build.ps1                        # dist/DSH-Pocket.apk
pwsh -File build.ps1 -Install -Launch -AdbSerial <serial>
```

Without a runtime you can still work on the launcher/UI shell:

```powershell
pwsh -File build.ps1 -AllowIncompleteRuntime -VersionName dev -VersionCode 0
```

## Where things live

| Area | Files |
|---|---|
| Launcher / service / runtime install | `app/src/com/dshpocket/{MainActivity,DshService,RuntimeInstaller,DshState,DshLog,Prefs}.java` |
| WebView + phone shell injection | `app/src/com/dshpocket/WebActivity.java`, `app/assets/mobile/skin.{css,js}` |
| Build/packaging | `build.ps1` |
| Runtime contract & gotchas | `ARCHITECTURE.md`, `runtime/notes-runtime.md` |
| Dev loop (CDP, screenshots, e2e) | `docs/DEV-WORKFLOW.md`, `tools/` |

## Rules of thumb

* **Don't bump `targetSdk`** above 28. The legacy app domain is what allows
  `execve()` of the embedded binaries from app storage. Bumping it silently breaks
  the whole runtime.
* Keep the APK signing key stable (`keystore/debug.keystore`) so updates install in
  place on existing phones.
* The phone UI is a *skin over DSH's desktop web UI*: prefer `data-slot="…"` selectors
  (stable) over hashed class prefixes (pinned to the bundled dsh version).
* Never write a MutationObserver that rewrites a node based on a condition that stays
  true after the rewrite — it loops forever and freezes the page (see CHANGELOG 1.12).
* Any change to the runtime payload changes `version.txt` and forces a rootfs
  re-extraction on upgrade (which also resets DSH state inside the guest). Only stage
  real runtime files in `build.ps1`.
* Test on the phone, not just the compile: `docs/DEV-WORKFLOW.md` has the screenshot
  and CDP commands.

## Pull requests

1. Keep the change focused; describe what you tested on which device/Android version.
2. Run `pwsh -File build.ps1 -AllowIncompleteRuntime` before pushing (CI does the same).
3. Update `CHANGELOG.md` for user-visible changes, and docs/screenshots when the UI
   changes.
4. For UI work, include before/after screenshots (`adb exec-out screencap`).
