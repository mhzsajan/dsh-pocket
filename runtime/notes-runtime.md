# DSH Pocket — verified PRoot runtime notes

Everything here was **verified end-to-end on a physical phone** (Samsung Galaxy S22 Ultra,
Android 16 / API 36, arm64-v8a, 4 KB pages) on 2026-10-24, driven over `adb` from
`/data/local/tmp/dshp-runtime/`. No Android NDK build, no jniLibs loader, no root required.

**Deliverables in this folder:** `proot`, `loader`, `lib/libtalloc.so.2`, `lib/libandroid-shmem.so`,
`start-dsh.sh`, `rootfs.tar.gz`, this file.

---

## 1. TL;DR for the Android app

Ship these under `<appFilesDir>/` exactly like this:

```
files/
  proot                      # Termux proot 5.1.107.100, bionic/arm64 (executable)
  loader                     # PRoot static loader (required, executable)
  lib/
    libtalloc.so.2           # proot dependency (Termux libtalloc 2.5.0)
    libandroid-shmem.so      # proot dependency (Termux libandroid-shmem 0.7)
  tmp/                       # writable dir; PRoot scratch (create it before launch)
  rootfs/                    # extract rootfs.tar.gz here
    root/start-dsh.sh        # guest entrypoint
    root/workspace/          # empty workspace dir
    usr/local/bin/node       # Node.js v22.23.4 (official linux-arm64)
    usr/local/lib/node_modules/@deepseek-ai/dsh   # preinstalled DSH 0.2.1-rc.2
  logs/                      # optional, app-side
```

Launch (child process env **and** argv — both matter):

```text
env:
  LD_LIBRARY_PATH  = <files>/lib
  PROOT_LOADER     = <files>/loader
  PROOT_TMP_DIR    = <files>/tmp
  PROOT_NO_SECCOMP = 1                # recommended; every successful run used it
  HOME             = /root
  TERM             = xterm-256color
  PATH             = /usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin

argv:
  <files>/proot -0 -l -r <files>/rootfs -b /dev -b /proc -b /sys -w /root \
               /bin/bash /root/start-dsh.sh
```

`start-dsh.sh` prints exactly one announcement line to stdout:

```
DSH_READY http://127.0.0.1:<port>/?token=<token>
```

and stays alive for as long as `dsh web` serves. The service should read stdout line by line,
match `^DSH_READY `, hand that URL to the WebView, and keep draining stdout. The same URL is
mirrored to `<files>/rootfs/root/dsh-web.url` (readable from the host side) as a fallback.

### Gotchas that will bite you

1. **`-env` is not supported** by this proot build (Termux's patched 5.1.107.100).
   `proot -env FOO=bar ...` → `proot error: unknown option '-env'`. Env must be set on the child
   process (`ProcessBuilder.environment()`).
2. **`loader` + the two `.so` files are mandatory.** `proot`'s compile-time loader path is
   `/data/data/com.termux/files/usr/libexec/proot/loader`; override with `PROOT_LOADER`.
   Its `DT_RUNPATH` is `/data/data/com.termux/files/usr/lib`; override with `LD_LIBRARY_PATH`.
   Without them, guest `execve()` fails with "Permission denied" / "loader was not found".
3. **Set `PROOT_TMP_DIR`.** Otherwise proot tries `/data/data/com.termux/files/usr/tmp/` and
   warns/fails ("Unable to create temp directory for f2fs bug probe").
4. **Android blocks `hardlink()`** (even for the `shell` user in `/data/local/tmp`, and for app
   processes in their data dir). Consequences:
   - `rootfs.tar.gz` contains **no hard links** (verified: `hardlinks=0`), so any extractor
     (Java, toybox `tar`) can unpack it.
   - Inside the guest `apt-get`/`dpkg` need `-l` (`--link2symlink`) on the proot command line,
     else dpkg fails with `unable to make backup link ... Permission denied`.
     `-l` is already in the launch argv.
5. **stdout must be drained for the whole session.** `start-dsh.sh` streams dsh output; if the
   app stops reading the pipe (64 KB) dsh blocks.
6. **Extract as the app user, don't chown.** Entries are `uid=0` (`--numeric-owner`); a non-root
   extractor leaves them owned by the app uid, which is exactly what PRoot `-0` wants. Ignore
   chown errors. ~1.02 GiB of file data, 72,398 files, ~80 KB of symlinks — extraction needs
   roughly 1.1 GB free and a minute or two on a phone.

## 2. Exact tested proot argv

```
LD_LIBRARY_PATH=/data/local/tmp/dshp-runtime/lib \
PROOT_LOADER=/data/local/tmp/dshp-runtime/loader \
PROOT_TMP_DIR=/data/local/tmp/dshp-runtime/tmp \
PROOT_NO_SECCOMP=1 \
HOME=/root TERM=xterm-256color PATH=/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin \
/data/local/tmp/dshp-runtime/proot -0 -l -r /data/local/tmp/dshp-runtime/rootfs \
  -b /dev -b /proc -b /sys -w /root \
  /bin/bash /root/start-dsh.sh
```

Bind mounts:
- `-b /dev`  — `/dev/ptmx`, `/dev/null`, `/dev/urandom`, `/dev/tty` for Node/npm/PTY/terminal.
- `-b /proc` — Node, `ps`, `git` need `/proc`.
- `-b /sys`  — harmless; some tools probe it.
- No `/tmp` bind: the guest's own `/tmp` (inside the rootfs) is used.
- `-0` fakes uid 0. `-l` emulates hard links with symlinks (Android hardlink denial).
- `-w /root`: guest cwd; DSH treats it as the initial workspace. `/root/workspace` exists; the
  app may later bind a real workspace over it (`-b <files>/workspace:/root/workspace`).

## 3. Guest image contents (verified versions)

| Component | Version |
|---|---|
| Ubuntu base | 24.04.5 (noble) arm64 base rootfs, glibc 2.39 |
| Node.js | v22.23.4 (official `node-v22.23.4-linux-arm64.tar.xz` → `/usr/local`) |
| npm | 10.9.9 (ships with Node 22; install scripts allowed — do NOT use Node 26/npm 12) |
| DSH | `@deepseek-ai/dsh@0.2.1-rc.2` (`npm i -g`), `dsh --version` → `0.2.1-rc.2` |
| PRoot | 5.1.107.100 (Termux main aarch64 deb, bionic-linked) |
| perl | 5.38.2 (real regular-file binaries, restored after link2symlink loss) |
| unzip / zipinfo | 6.00 |
| git | 2.43.0 |
| curl | 8.5.0 + working ca-certificates (HTTPS 200 verified to registry.npmjs.org and ports.ubuntu.com) |
| other CLI | `xz` 5.4.5, `less`, `nano`, `gzip`, `tar`, `bash`, `dpkg`/`apt` |
| xdg-user-dirs | 0.18-1build1 (`xdg-user-dir DOCUMENTS` → `/root/workspace`) |
| koffi | prebuilt `@koromix/koffi-linux-arm64/linux_arm64/koffi.node` — **no gyp compile** |
| node-pty | prebuilt `prebuilds/linux-arm64/pty.node` — **no gyp compile** |

`python3` is **not** included (would push the .tar.gz past the ~320 MB budget); it is
apt-installable inside the guest later.

Pre-warmed state: `/root/.dsh` profile scaffolding is included (a warm first run). Test-only
state was stripped: `.dsh/.credentials.yaml` (my test's browser-session grant secret) and
`.dsh/.anonymous-user-id` are deliberately absent; dsh regenerates them on first run.

### First-run workspace banner fix (`xdg-user-dirs`) — required, do not remove

On Linux, `@deepseek-ai/dsh-api-workspace-controller` discovers the first-use workspace root by
running `xdg-user-dir DOCUMENTS`. Without `xdg-user-dir` (or without a Documents dir) DSH throws
and the Web UI shows the banner *"Unable to create default workspace. Choose workspace…"*.
Fix baked into the image:

1. `apt-get install -y xdg-user-dirs` (real Ubuntu package; it lives in `/usr/bin`, so never
   shadow it with a `/usr/local/bin` stub).
2. `/root/.config/user-dirs.dirs` contains exactly one line:
   `XDG_DOCUMENTS_DIR="$HOME/workspace"`
3. `/root/workspace` exists (the app may bind its persisted workspace over it, e.g.
   `-b <files>/workspace:/root/workspace`).
4. Verified: `HOME=/root xdg-user-dir DOCUMENTS` → `/root/workspace`.
   No `Desktop`/`Documents`/`Downloads`/… dirs are created in `/root`; only `workspace/`.

## 4. Network / DNS

PRoot shares the host network stack — no NAT, no user-mode networking; the phone's own
connectivity is used directly.

- `/etc/resolv.conf` is a normal file with `nameserver 8.8.8.8` / `1.1.1.1`; `start-dsh.sh`
  recreates it if empty.
- **IPv6 gotcha:** this carrier/Wi-Fi setup has no usable IPv6 route, but DNS returns AAAA
  records first, so `apt-get update` can hang. Force IPv4 for apt:
  `apt-get -o Acquire::ForceIPv4=true ...`. `curl -4` and `npm` work fine.
- APT lists were stripped to save size. Before installing packages run once in the guest
  (`-l` on the proot command line makes dpkg's hard-link backups work):
  ```
  apt-get -o Acquire::ForceIPv4=true update
  apt-get install -y <pkg>
  ```

## 5. Measured behaviour on the phone

- `proot --version` works from a bare `adb shell` (no root).
- Guest boot (`/bin/bash -c 'uname -a; echo OK'`): < 1 s.
- `npm i -g @deepseek-ai/dsh@0.2.1-rc.2` (cold, on-device): ~6 min, ~885 MB in
  `/usr/local/lib/node_modules` (~1 GB with Node); every native dep prebuilt.
- `dsh web --no-open --host 127.0.0.1 --port 0`: prints its URL within a few seconds of node
  boot (cold PRoot→`DSH_READY` observed ≈ 2-10 s).
- PC check through `adb forward tcp:5399 tcp:<port>`:
  - `GET /` without token → **401** (auth fence)
  - `GET /?token=...` → **303** + `Set-Cookie: dsh-auth-...`
  - `GET ./` with that cookie → **200**, `text/html`, 37,512 bytes, contains
    `window.__DSH_BOOT__` (the real DSH Web UI)
- `start-dsh.sh` emitted, verbatim, on two runs:
  `DSH_READY http://127.0.0.1:45921/?token=lmXZAqdi8xPuQvcr5mokBegdjzGQxlAkw4UwPNBx8hw`
  `DSH_READY http://127.0.0.1:39075/?token=8dOxoMsXZ3ZjvJr8i_kKlnLlKTE7jLUe1IC8izhv6jg`
- Hardlink-denial reproduction: `ln hl1 hl2` in `/data/local/tmp` → `Permission denied`
  (this is why `-l` exists).

## 6. Payload audit (final `rootfs.tar.gz`)

| Metric | Value |
|---|---|
| File size | **328,999,293 bytes** (313.8 MiB / 329.0 MB) |
| SHA-256 | `f433239baebbe0fb5824d6ec52a1e6588b0146d539bdc43e039b24e050f01bda` |
| Tar entries | **80,377** (files 72,413, dirs 7,339, symlinks 625) |
| Hard link entries | **0** |
| Dangling symlinks (guest semantics) | **0** |
| Absolute-target symlinks | 168 — all resolve inside the rootfs (`/bin`→`usr/bin`, `/lib`→`usr/lib`, ca-certificates, alternatives); none point at `/data/...` |
| ` -> /data/` symlink targets | **0**; the `/data/...` test-cache tree is not in the archive |
| `l2s` (PRoot link2symlink) entries | **0** |
| Uncompressed file bytes | 1,092,310,106 (~1.02 GiB) |
| Validation | `tar -tzf` exit **0** (bsdtar, Windows); GNU `tar -tzf` exit 0 on-phone; extraction re-test + `DSH_READY` on device; phone sha256 == PC sha256 |

Required paths confirmed present in the archive: `./usr/bin/xdg-user-dir`,
`./root/.config/user-dirs.dirs`, `./usr/bin/perl`, `./usr/bin/unzip`, `./usr/bin/git`,
`./usr/local/bin/node`, `./usr/local/bin/dsh`, `./usr/bin/bash`, `./root/start-dsh.sh`,
`./root/workspace/`. The archived `start-dsh.sh` is byte-identical to the staged one.

## 7. How `rootfs.tar.gz` was produced (exact final recipe)

```sh
#!/system/bin/sh
RT=/data/local/tmp/dshp-runtime
echo "TAR START $(date)" > $RT/tar2.log
cd $RT/rootfs || exit 1
tar -cf - . 2>$RT/tar2err.txt | gzip -1 > $RT/rootfs2.tar.gz
echo "TAR DONE rc=$? $(date)" >> $RT/tar2.log
sync
```

i.e. **Android toybox tar, not GNU tar inside proot.** Why: archiving from inside PRoot makes
every read syscall pass through proot's ptrace, and long runs were repeatedly lost when the
USB/adb session blipped (`adb shell` death killed the traced tree); toybox tar on the host side
takes ~20-30 s for the whole tree, writes no stderr, and needs no ptrace. `adb pull` the result
(330 MB at ~40 MB/s, ~8 s).

**Pre-tar checklist (important for any future re-tar of this rootfs):**

1. Kill guest dsh/proot processes.
2. Delete the PRoot `--link2symlink` native cache: strip
   `rootfs/data/local/tmp/node-addon-native-custom-loader-0` and the now-empty `rootfs/data`
   tree. It contains **absolute symlinks to the test machine's path**
   (`/data/local/tmp/dshp-runtime/rootfs/...`), which dangle on any other device.
   Also remove other test-only junk under `rootfs/data/local/tmp/` (`dsh-spill-*`,
   `node-compile-cache`) and `rootfs/tmp/*`.
3. Strip test state/secrets and package-manager caches:
   `root/.dsh/.credentials.yaml`, `root/.dsh/.anonymous-user-id`, `root/dsh-web.log`,
   `root/dsh-web.url`, `root/.npm-cache`, `root/xdg-fix.sh`, `root/xdg.log`,
   `/var/lib/apt/lists/*`, `/var/cache/apt/archives/*`, and — **critical** — the rest of
   `/var/cache/apt/*`. `apt-get update` leaves `pkgcache.bin`/`srcpkgcache.bin` (~107 MB here);
   forgetting them inflates the .tar.gz by ~44 MB. Also delete a `var/lib/dpkg/.l2s.status*`
   scratch pair if a `-l` apt run left one.
4. Keep the `xdg` fix: `/root/.config/user-dirs.dirs` = `XDG_DOCUMENTS_DIR="$HOME/workspace"`,
   `/root/workspace/` present, `usr/bin/xdg-user-dir` present (see §3).
5. Audit before tarring:
   - `find rootfs -type f -links +1` → must be 0 (hard links are unusable on Android).
   - scan symlinks for dangling/absolute host paths → must be 0.
6. Archive with toybox tar (`cd rootfs && tar -cf - . | gzip -1 > rootfs.tar.gz`), then validate
   on the PC: `tar -tzf` exit 0, entry count, `hardlinks=0`, `dangling=0`, required binaries.

Known tar pitfalls seen on this project: `--hard-dereference`/`--one-file-system` were
attempted inside PRoot; the run is very slow and can be killed with the adb session, and
leftover `.l2s.*` scratch symlinks make tar print "Too many levels of symbolic links".
Checking for hard links and dangling symlinks beforehand (step 4) removes the need for those
flags entirely.

### Repair history (why perl/unzip are plain files)

`proot -l` turns `link()` into symlinks towards hidden `.l2s.*` files. Deleting those scratch
files broke 9 entries: `/usr/bin/{perl,perl5.38.2,perlbug,perlthanks}`, `/usr/bin/{unzip,zipinfo}`,
`/var/lib/dpkg/status-old` and a node-addon cache tree. Repair:
- `perl`, `perl5.38.2` (perl-base 5.38.2-3.2ubuntu0.6) and `perlbug`, `perlthanks` (perl package)
  were extracted from the arm64 .debs as **real regular files** and pushed back;
- `unzip`, `zipinfo` (unzip 6.0-28ubuntu4.1) likewise;
- 45 dangling coreutils locale links + 9 dangling doc/changelog links (all cosmetic, same cause)
  were removed;
- `dpkg --audit` → clean, `perl -v`, `unzip -v`, `perl -e 'print "PERL_OK\n"'` verified, and
  `dsh web` re-tested (`DSH_READY`) after the repair.

## 8. Not verified / limitations

- The DSH Web UI was verified as HTTP+HTML from the PC (`__DSH_BOOT__` present); rendering in
  the APK's WebView and the app's service wiring are the app teammate's E2E test.
- `dsh plugin ...` (pnpm-based) was not tested: pnpm is not installed; it reports
  `pnpm-missing`. Install with `npm i -g pnpm` inside the guest if the plugin manager is needed.
- Only `dsh web` was exercised; `dsh headless`/`tui` were not.
- `PROOT_NO_SECCOMP=1` was set for every successful run. It is likely not strictly required
  (apt succeeded without it once) but keep it for stability; it only costs some speed.
- `/data/local/tmp/dshp-runtime/` on the phone is disposable; the app must not reference it.
