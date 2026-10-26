# Runtime assets (not committed)

`build.ps1` stages everything in this directory into the APK (`app/assets/runtime/`).
Two of the five files are fetchable (small binaries); the rootfs must be built once
on an **arm64 Linux/Android** machine and is ~314 MB, so it never lives in git.

```
runtime/
  proot                  # Termux PRoot 5.1.107.100, aarch64 (fetch-assets.ps1)
  loader                 # PRoot ELF loader, aarch64            (fetch-assets.ps1)
  lib/libtalloc.so.2     # PRoot dependency                     (fetch-assets.ps1)
  lib/libandroid-shmem.so# PRoot dependency                     (fetch-assets.ps1)
  rootfs.tar.gz          # Ubuntu 24.04 arm64 + Node 22 + dsh   (build it, see below)
  start-dsh.sh           # guest entrypoint (committed, source)
  notes-runtime.md       # verified device notes (committed)
```

Required check (what `build.ps1` enforces):

```powershell
pwsh -File runtime/fetch-assets.ps1
```

This downloads the Termux `.deb` packages, extracts the four binaries into
`runtime/`, and also downloads the Ubuntu base + Node.js tarballs into
`runtime/downloads/` as inputs for the rootfs build.

## 1. Host binaries (automatic)

Sources (Termux packages, aarch64): `proot`, `libtalloc`, `libandroid-shmem`.
They are shipped as plain files (not `jniLibs`) because the app deliberately
targets SDK 28, whose legacy SELinux domain may `execve` files from the app data
dir — the same trick Termux itself uses.

## 2. Rootfs (build once on arm64)

You need an arm64 Linux environment. Easiest: a phone with Termux, or an arm64
Linux box. The steps below are exactly what produced the current image
(see `notes-runtime.md` for the full log).

```bash
# on an arm64 host with proot available
mkdir -p rootfs && tar -xzf ubuntu-base-24.04.5-base-arm64.tar.gz -C rootfs

# enter the image (no root needed)
proot -0 -r rootfs -b /dev -b /proc -b /sys -w /root /bin/bash

# --- inside the image ---
export PATH=/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin
export DEBIAN_FRONTEND=noninteractive
apt-get -o Acquire::ForceIPv4=true update
apt-get install -y --no-install-recommends \
    git curl ca-certificates unzip xz-utils less nano xdg-user-dirs

# Node 22 (official linux-arm64 glibc build) into /usr/local
#   wget https://nodejs.org/dist/latest-v22.x/node-v22.*-linux-arm64.tar.xz
tar -xJf node-v22.*-linux-arm64.tar.xz -C /usr/local --strip-components=1

# dsh itself; koffi + node-pty resolve from arm64 prebuilts (no compilation)
npm i -g @deepseek-ai/dsh@0.2.1-rc.2
dsh --version        # 0.2.1-rc.2

# workspace + Documents mapping so the web UI starts on "Default workspace"
mkdir -p /root/workspace /root/.config
printf 'XDG_DOCUMENTS_DIR="$HOME/workspace"\n' > /root/.config/user-dirs.dirs
HOME=/root xdg-user-dir DOCUMENTS   # -> /root/workspace

# cleanup before packing (apt cache is ~100 MB and must not ship)
rm -rf /var/cache/apt/* /var/lib/apt/lists/* /tmp/* /root/.npm/_cacache
find / -name '.l2s.*' -delete 2>/dev/null || true
rm -f /root/.dsh/.credentials.yaml /root/.dsh/.anonymous-user-id
```

Copy `runtime/start-dsh.sh` to `/root/start-dsh.sh` inside the image.

Pack (relative paths, hardlink-free — Android cannot create hard links):

```bash
tar -I "gzip -1" --hard-dereference --numeric-owner --one-file-system \
    --exclude='./proc/*' --exclude='./sys/*' --exclude='./dev/*' \
    --exclude='./tmp/*' --exclude='./run/*' \
    -cf rootfs.tar.gz .
```

Expected result: ~1.09 GB extracted, ~72 k files, **~314 MB** compressed,
0 hardlinks, no dangling symlinks. Validate before use:

```powershell
tar -tzf runtime/rootfs.tar.gz > $null   # exit 0
tar -tvzf runtime/rootfs.tar.gz | Select-String ' -> /'  # no host-path symlinks
```

## What the app does with these files

1. copies `proot` + `loader` to `files/`, `lib/*` to `files/lib/`, creates `files/tmp/`
2. extracts `rootfs.tar.gz` to `files/rootfs/` (Java ustar reader, ~15 s)
3. runs:
   ```
   files/proot -0 -l -r files/rootfs -b /dev -b /proc -b /sys \
       -b files/workspace:/root/workspace [-b /storage/emulated/0:/sdcard] \
       -w /root /bin/bash /root/start-dsh.sh
   ```
   with `LD_LIBRARY_PATH=files/lib`, `PROOT_LOADER=files/loader`,
   `PROOT_TMP_DIR=files/tmp`, `PROOT_NO_SECCOMP=1` (proot `-env` is not supported
   by this build).

Any change to these assets changes `version.txt` and makes the app re-extract the
rootfs on next start (the host workspace is preserved).
