# Third-party components

The repository itself contains only original source code (MIT, see `LICENSE`).
The **built APK** bundles a Linux runtime. If you distribute the APK, you take
over the obligations of the licenses below.

| Component | Used as | License | Where |
|---|---|---|---|
| [PRoot](https://proot.gitlab.io/proot/) (Termux build 5.1.107.100) | `proot` + `loader` (aarch64) | **GPL-2.0-or-later** | fetched by `runtime/fetch-assets.ps1`, embedded in APK assets |
| libtalloc (Termux build) | `libtalloc.so.2` | LGPL-3.0-or-later | same |
| libandroid-shmem (Termux build) | `libandroid-shmem.so` | BSD-2-Clause | same |
| Ubuntu Base 24.04 arm64 | rootfs (`rootfs.tar.gz`) | many (GPL/LGPL/Apache/BSD…) | see `/usr/share/doc` inside the rootfs |
| Node.js 22 (linux-arm64) | `/usr/local/bin/node` | MIT | https://nodejs.org |
| DeepSeek Harness (`@deepseek-ai/dsh`) | `/usr/local/lib/node_modules/@deepseek-ai/dsh` | MIT | https://github.com/deepseek-ai/deepseek-harness |
| koffi, node-pty and other npm deps | inside the dsh install | MIT / see packages | npm registry |
| Android `toybox` (from the test phone) | `app/assets/probe/toybox` self-test payload | Apache-2.0 | AOSP |
| Android SDK build-tools (aapt2, d8, zipalign, apksigner) | build only, not redistributed | Apache-2.0 | local SDK |

Notes for redistribution:

* **PRoot is GPL-2.0-or-later.** If you ship an APK that contains the `proot`
  and `loader` binaries, you must also make the corresponding PRoot source
  available and state the license. Source: <https://github.com/termux/proot>
  (Termux fork, tag matching 5.1.107.100) and upstream <https://proot.gitlab.io>.
* The rootfs is a standard Ubuntu arm64 base with Ubuntu packages installed by
  `apt`; the license texts stay inside the image at `/usr/share/doc/*`.
* `keystore/debug.keystore` is an Android **debug** key (`pass: android`) used
  only to sign sideload builds. It is not a secret and is not suitable for
  Google Play.
