#!/usr/bin/env python3
"""Build a LARGE mock runtime (big rootfs.tar.gz) to dry-run extraction performance.

Not used for the real APK - only for testing the installer/progress UI with realistic sizes.
Writes dsh-pocket/build/dev-runtime-big/ (proot/loader/lib/start-dsh.sh copied from the small
mock runtime so the plumbing still works).

    python make_mock_runtime_big.py
    pwsh dsh-pocket/build.ps1 -RuntimeSource dsh-pocket/build/dev-runtime-big
"""
import gzip
import io
import os
import random
import shutil
import tarfile

HERE = os.path.dirname(os.path.abspath(__file__))
SMALL = os.path.normpath(os.path.join(HERE, "..", "..", "build", "dev-runtime"))
OUT = os.path.normpath(os.path.join(HERE, "..", "..", "build", "dev-runtime-big"))


def main():
    if os.path.isdir(OUT):
        shutil.rmtree(OUT)
    shutil.copytree(SMALL, OUT)
    os.remove(os.path.join(OUT, "rootfs.tar.gz"))

    rng = random.Random(42)
    chunk_pool = [bytes(rng.randrange(256) for _ in range(4096)) for _ in range(16)]
    start_script = open(os.path.join(SMALL, "start-dsh.sh"), "rb").read()

    buf = io.BytesIO()
    total = 0
    with tarfile.open(fileobj=buf, mode="w", format=tarfile.GNU_FORMAT) as tar:
        def add_dir(name):
            info = tarfile.TarInfo(name)
            info.type = tarfile.DIRTYPE
            info.mode = 0o755
            info.mtime = 1700000000
            tar.addfile(info)

        def add_file(name, data, mode=0o644):
            info = tarfile.TarInfo(name)
            info.size = len(data)
            info.mode = mode
            info.mtime = 1700000000
            tar.addfile(info, io.BytesIO(data))

        for d in ("bin", "usr", "usr/bin", "usr/lib", "usr/share", "etc", "root", "workspace", "var/tmp"):
            add_dir(d)
        add_file("bin/bash", b"#!/system/bin/sh\necho mock bash\n", 0o755)
        add_file("root/start-dsh.sh", start_script, 0o755)

        # package-manager-ish tree: many medium files
        n_files = 0
        for bucket in range(40):
            add_dir("usr/share/mock/pkg%02d" % bucket)
            for i in range(50):
                path = "usr/share/mock/pkg%02d/file%03d.dat" % (bucket, i)
                data = b"".join(rng.choice(chunk_pool) for _ in range(4))  # ~16KB
                add_file(path, data)
                total += len(data)
                n_files += 1

        # a few bigger files
        for i in range(6):
            data = b"".join(rng.choice(chunk_pool) for _ in range(2560))  # ~10MB
            add_file("usr/lib/biglib%02d.so" % i, data)
            total += len(data)
            n_files += 1

    raw = buf.getvalue()
    gz = gzip.compress(raw, 4)
    with open(os.path.join(OUT, "rootfs.tar.gz"), "wb") as f:
        f.write(gz)
    print("big mock runtime:", OUT)
    print("  files=%d  tar=%d MB  gz=%d MB" % (n_files, len(raw) // (1024 * 1024), len(gz) // (1024 * 1024)))


if __name__ == "__main__":
    main()
