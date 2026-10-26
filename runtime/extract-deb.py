# Extract a Debian .deb (ar archive) using only the Python standard library.
#   python extract-deb.py <file.deb> <outdir>
import io
import lzma
import os
import sys
import tarfile


def read_ar(data: bytes):
    """Yield (name, payload) for each ar member."""
    if not data.startswith(b"!<arch>\n"):
        raise SystemExit("not an ar archive")
    off = 8
    while off + 60 <= len(data):
        header = data[off:off + 60]
        name = header[0:16].decode("utf-8", "replace").strip().rstrip("/")
        size = int(header[48:58].decode("ascii").strip())
        body = data[off + 60:off + 60 + size]
        yield name, body
        off += 60 + size + (size & 1)


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__ or "usage: extract-deb.py <file.deb> <outdir>", file=sys.stderr)
        return 2
    deb, outdir = sys.argv[1], sys.argv[2]
    data = open(deb, "rb").read()
    os.makedirs(outdir, exist_ok=True)
    for name, body in read_ar(data):
        if not name.startswith("data.tar"):
            continue
        if name.endswith(".xz"):
            raw = lzma.decompress(body)
        elif name.endswith(".gz"):
            import gzip
            raw = gzip.decompress(body)
        else:  # plain data.tar
            raw = body
        with tarfile.open(fileobj=io.BytesIO(raw)) as tf:
            tf.extractall(outdir)
        print(f"extracted {name} -> {outdir}")
        return 0
    raise SystemExit("no data.tar member found")


if __name__ == "__main__":
    raise SystemExit(main())
