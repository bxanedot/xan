#!/usr/bin/env python3
"""Write a SHA-256 manifest for the exact release files passed on the CLI."""

from __future__ import annotations

import hashlib
import sys
from pathlib import Path


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main(arguments: list[str]) -> int:
    if len(arguments) < 3:
        print("usage: write_checksums.py OUTPUT FILE [FILE ...]", file=sys.stderr)
        return 2
    output = Path(arguments[1])
    files = [Path(value) for value in arguments[2:]]
    missing = [path for path in files if not path.is_file() or path.stat().st_size == 0]
    if missing:
        print("Missing or empty release file(s): " + ", ".join(map(str, missing)), file=sys.stderr)
        return 1
    output.write_text("".join(f"{sha256(path)}  {path.name}\n" for path in files), encoding="ascii")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
