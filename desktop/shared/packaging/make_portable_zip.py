#!/usr/bin/env python3
"""Archive a jpackage app-image under a single XAN/ folder."""

from __future__ import annotations

import sys
import zipfile
from pathlib import Path


def main() -> int:
    if len(sys.argv) != 3:
        print("usage: make_portable_zip.py APP_IMAGE_DIR OUTPUT_ZIP", file=sys.stderr)
        return 2
    source = Path(sys.argv[1])
    output = Path(sys.argv[2])
    if not source.is_dir() or not (source / "XAN.exe").is_file():
        print(f"Expected a Windows XAN app image with XAN.exe: {source}", file=sys.stderr)
        return 1
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for file in sorted(path for path in source.rglob("*") if path.is_file()):
            archive.write(file, Path("XAN") / file.relative_to(source))
    if output.stat().st_size == 0:
        output.unlink(missing_ok=True)
        raise RuntimeError("Portable archive was empty.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
