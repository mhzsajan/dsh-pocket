# Development workflow (build → phone → inspect → iterate)

Everything runs on the phone; the PC only builds the APK and talks to it over `adb`.

## 1. Build & install

```powershell
# build only
pwsh -File dsh-pocket/build.ps1

# build + install + launch on the test phone
pwsh -File dsh-pocket/build.ps1 -Install -Launch

# different device serial
pwsh -File dsh-pocket/build.ps1 -Install -Launch -AdbSerial <serial>

# UI-only iteration without the 314 MB runtime (fast build, boots to launcher)
pwsh -File dsh-pocket/build.ps1 -VersionName 0.0-dev -AllowIncompleteRuntime
```

`build.ps1` picks the newest `build-tools` with `aapt2/d8/apksigner/zipalign`, the
platform `android-35` `android.jar`, and `JAVA_HOME`'s JDK. No Gradle, no AndroidX.
Signing uses `keystore/debug.keystore` (committed) so every build updates in place.

## 2. Look at the app

```powershell
# screenshot (PowerShell ">" corrupts binary output — always use cmd /c)
cmd /c "adb -s <serial> exec-out screencap -p > shot.png"

# app logs
adb -s <serial> logcat -d -v brief | Select-String 'DshPocket'
```

The launcher screen shows a live runtime log and a **Self-test** (proot version +
exec probes) and **Copy log** for bug reports.

## 3. Inspect / restyle the web UI live (CDP)

The WebView has debugging enabled. `tools/cdp.mjs` is a tiny DevTools client:

```powershell
$appPid = (adb -s <serial> shell pidof com.dshpocket).Trim()
# IMPORTANT: forwards do not survive between shell invocations — create it in the
# same command as the node call.
adb -s <serial> forward tcp:9222 "localabstract:webview_devtools_remote_$appPid"
node dsh-pocket/tools/cdp.mjs eval "JSON.stringify({url:location.href, ready:document.readyState})"
node dsh-pocket/tools/cdp.mjs outline           # DOM tree with geometry
node dsh-pocket/tools/cdp.mjs shot out.png      # screenshot of the page
node dsh-pocket/tools/cdp.mjs inject dsh-pocket/app/assets/mobile/skin.css dsh-pocket/app/assets/mobile/skin.js
node dsh-pocket/tools/cdp.mjs tap 200 600       # tap in CSS pixels
node dsh-pocket/tools/cdp.mjs key Escape
```

Notes:

* `inject` updates the live page only; to test a fresh copy reload the page and
  force a re-install of the skin:
  `window.__dshpInstalled=false; document.getElementById('dshp-nav')?.remove();`
  then `inject` again (the app's injected copy otherwise short-circuits).
* **Never put a length-based condition in a MutationObserver that rewrites the same
  node** — an earlier placeholder rewrite looped forever and froze the page thread
  (taps stopped working). Compare exact strings.
* The phone and the PC share the adb serial; don't run two installs at once.

## 4. End-to-end tests

```powershell
pwsh -File dsh-pocket/tools/final-e2e.ps1     # cold start → DSH_READY → HTTP → screenshot
pwsh -File dsh-pocket/tools/e2e.ps1           # build + install + HTTP auth-flow check
```

Both expect the app installed; `final-e2e.ps1` clears app data first (true cold run,
~50 s).

## 5. Runtime experiments (no APK)

The runtime itself is plain files; you can drive it with `adb shell` without the app:

```bash
adb push dsh-pocket/runtime/* /data/local/tmp/dshp/
adb shell "cd /data/local/tmp/dshp && ./proot -0 -l -r rootfs -b /dev -b /proc -b /sys \
    -w /root /bin/bash /root/start-dsh.sh"
```

See `runtime/notes-runtime.md` for the exact environment (`LD_LIBRARY_PATH`,
`PROOT_LOADER`, `PROOT_TMP_DIR`, `PROOT_NO_SECCOMP`) and every gotcha found on the
device (hardlinks, DNS/IPv4, xdg-user-dirs, credentials).

## 6. UI skin

The phone UI is a **CSS + JS shell injected by `WebActivity`** after each page load
(`app/assets/mobile/skin.css`, `skin.js`; wired in `WebActivity.injectSkin`). It
overrides DSH's desktop layout without patching DSH itself:

* no permanent 56 px icon rail, full-width chat
* sidebar / right panel = fixed overlay drawers with scrim
* popovers = bottom sheets; dialogs resized + sticky close
* injected bottom nav: Chats / New / More (bridged to Java for the native menu)
* back closes drawer/dialog first (`window.__dshpBack`)

If DSH's DOM changes in a future version, the `data-slot="..."` hooks are the most
stable selectors; hashed class prefixes (`pI_x6G_*`, `VOzbGW_*`, …) are pinned to
the bundled dsh version.
