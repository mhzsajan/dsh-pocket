#!/usr/bin/env python3
"""Create a MOCK runtime tree used only to test the app plumbing (NOT the real runtime).

Writes to dsh-pocket/build/dev-runtime/:
  proot          # shell script pretending to be proot; execs the guest start script
  loader         # dummy
  lib/libtalloc.so.2, lib/libandroid-shmem.so   # dummy
  start-dsh.sh   # mock guest entrypoint: serves a tiny HTTP page with toybox nc,
                 # prints "DSH_READY http://127.0.0.1:5399/?token=mocktoken"
  rootfs.tar.gz  # GNU tar with dirs/files/symlinks/long names, extracted by the app

Build with:  pwsh dsh-pocket/build.ps1 -RuntimeSource dsh-pocket/build/dev-runtime
"""
import io
import os
import stat
import tarfile
import gzip

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.normpath(os.path.join(HERE, "..", "..", "build", "dev-runtime"))

PROOT = r"""#!/system/bin/sh
# mock proot for DSH Pocket plumbing tests - NOT the real proot
echo "mock-proot: argv: $*"
echo "mock-proot: LD_LIBRARY_PATH=$LD_LIBRARY_PATH"
echo "mock-proot: PROOT_LOADER=$PROOT_LOADER PROOT_TMP_DIR=$PROOT_TMP_DIR"
for last; do :; done
case "$last" in
  */start-dsh.sh|/root/start-dsh.sh) exec /system/bin/sh "$PWD/rootfs${last}" ;;
esac
echo "mock-proot: no guest start script in argv" >&2
exit 91
"""

START_SCRIPT = r"""#!/system/bin/sh
# mock guest entrypoint (serves a tiny page so the WebView has something real to load)
PORT=5399
echo "mock-dsh: starting fake dsh web"
echo "mock-dsh: node $(uname -m) (mock)"
printf 'DSH_READY http://127.0.0.1:%s/?token=mocktoken\n' "$PORT"
trap 'exit 0' TERM INT
while true; do
  {
    printf 'HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nConnection: close\r\n\r\n'
    printf '<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><title>DSH Pocket mock</title></head>'
    printf '<body style="margin:0;background:#0b1220;color:#ecf2ff;font-family:sans-serif;padding:2em">'
    printf '<h1 style="color:#4c8dff">DSH Pocket</h1><h2>MOCK WEB UI OK</h2>'
    printf '<p>This page is served by the mock runtime through the app WebView.</p>'
    printf '<p>DSH_READY URL was parsed from the guest stdout.</p></body></html>'
  } | /system/bin/toybox nc -l -p "$PORT" 2>/dev/null
done
"""


def main():
    os.makedirs(OUT, exist_ok=True)
    lib = os.path.join(OUT, "lib")
    os.makedirs(lib, exist_ok=True)

    def write(path, data, mode=0o755):
        full = os.path.join(OUT, path)
        os.makedirs(os.path.dirname(full) or OUT, exist_ok=True)
        with open(full, "wb") as f:
            f.write(data if isinstance(data, bytes) else data.encode())
        os.chmod(full, mode)

    write("proot", PROOT, 0o755)
    write("loader", "mock loader - not a real ELF\n", 0o755)
    write("lib/libtalloc.so.2", "mock libtalloc\n", 0o644)
    write("lib/libandroid-shmem.so", "mock libandroid-shmem\n", 0o644)
    write("start-dsh.sh", START_SCRIPT, 0o755)

    # ---- mock rootfs.tar.gz (GNU format so long names use an 'L' entry) ----
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w", format=tarfile.GNU_FORMAT) as tar:
        def add_dir(name, mode=0o755):
            info = tarfile.TarInfo(name)
            info.type = tarfile.DIRTYPE
            info.mode = mode
            info.mtime = 1700000000
            tar.addfile(info)

        def add_file(name, data, mode=0o755):
            raw = data.encode()
            info = tarfile.TarInfo(name)
            info.size = len(raw)
            info.mode = mode
            info.mtime = 1700000000
            tar.addfile(info, io.BytesIO(raw))

        def add_symlink(name, target):
            info = tarfile.TarInfo(name)
            info.type = tarfile.SYMTYPE
            info.linkname = target
            info.mode = 0o777
            tar.addfile(info)

        add_dir("bin")
        add_dir("usr")
        add_dir("usr/bin")
        add_dir("usr/lib64")
        add_dir("etc")
        add_dir("root", 0o700)
        add_dir("workspace")
        add_file("bin/bash", "#!/system/bin/sh\necho mock bash $*\n")
        add_file("usr/bin/node", "#!/system/bin/sh\necho mock node v22\n")
        add_file("root/start-dsh.sh", START_SCRIPT)
        add_file("etc/os-release", "NAME=\"Mock Ubuntu\"\nVERSION_ID=\"24.04\"\n")
        add_symlink("lib64", "usr/lib64")
        add_symlink("etc/localtime", "/usr/share/zoneinfo/UTC")
        # dangling symlinks: reproduce the proot --link2symlink artifacts found in the real
        # rootfs (.l2s.* targets are deleted scratch files) - extraction must tolerate them
        add_symlink("usr/bin/perl", "/usr/bin/.l2s.perl5.36.0")
        add_symlink("usr/bin/unzip", ".l2s.unzip6.0")
        deep = "usr/share/mock/" + "/".join("d%02d" % i for i in range(12)) + "/long-name-file.txt"
        add_file(deep, "long name path extracted fine\n")
        # a hardlink entry (the real rootfs is built with --hard-dereference; parser must survive)
        link = tarfile.TarInfo("bin/bash-hardlink")
        link.type = tarfile.LNKTYPE
        link.linkname = "bin/bash"
        link.mode = 0o755
        tar.addfile(link)

    raw = buf.getvalue()
    gz = gzip.compress(raw, 6)
    with open(os.path.join(OUT, "rootfs.tar.gz"), "wb") as f:
        f.write(gz)
    print("mock runtime written to", OUT)
    print("  rootfs.tar.gz:", len(raw), "bytes tar,", len(gz), "bytes gzipped")


if __name__ == "__main__":
    main()
