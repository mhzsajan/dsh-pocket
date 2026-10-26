# Security policy

## Reporting a vulnerability

Please **do not open a public issue** for security problems. Use GitHub's private
vulnerability reporting:

<https://github.com/mhzsajan/dsh-pocket/security/advisories/new>

Include the app version (`versionName` shown in the launcher), Android version/ABI,
and reproduction steps. You can expect an acknowledgement within a few days.

## Threat model & design notes

* **Local-only server.** `dsh web` binds `127.0.0.1` on a random port and every run
  prints a fresh token URL; the WebView loads exactly that URL. Nothing listens on the
  LAN and there is no inbound exposure.
* **No telemetry.** The app contains no analytics, crash reporting or tracking SDKs.
  Network traffic comes from DSH itself (DeepSeek API / sign-in) and from package
  managers you invoke inside the guest.
* **Data locality.** Sessions, API keys, the Linux rootfs and your workspace live in
  the app's private storage (`/data/data/com.dshpocket`). `/sdcard` inside the guest is
  only mounted after you grant the storage permission. Uninstalling removes the private
  data.
* **The guest is not a security boundary.** Inside the Linux userland the agent runs as
  fake-root (PRoot) and can read what you mounted into it. Treat everything you place in
  the app's workspace as visible to the model/session.
* **Signing key.** Releases are signed with the committed Android **debug** keystore
  (`pass: android`), which is public by design so updates install in place. That also
  means anyone can produce an update-compatible APK — only install builds that come from
  this repository's releases. If you need stronger guarantees, sign your own builds with
  a private keystore that you never commit.
* **Third-party binaries.** The APK embeds PRoot (GPL-2.0-or-later) and an Ubuntu
  rootfs; see [THIRD-PARTY.md](THIRD-PARTY.md). The build fetches PRoot from the Termux
  package repository (`runtime/fetch-assets.ps1`) and verifies the ELF architecture.
