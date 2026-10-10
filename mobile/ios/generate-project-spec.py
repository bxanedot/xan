"""Generate XcodeGen version settings from the Android release source of truth."""

import os
import re
import subprocess
import sys
from pathlib import Path


def generate_spec(root: Path, release_tag: str = "") -> Path:
    if sys.platform == "darwin":
        subprocess.run(["xcrun", "swift", str(root / "generate-app-icon.swift"), str(root)], check=True)
    properties = {}
    for line in (root / "../android/version.properties").read_text(encoding="utf-8").splitlines():
        if line.strip() and not line.lstrip().startswith("#"):
            key, value = line.split("=", 1)
            properties[key.strip()] = value.strip()
    version = release_tag.removeprefix("v") if release_tag else properties["XAN_VERSION"]
    if not re.fullmatch(r"\d+\.\d+\.\d+(?:\.\d+)?", version):
        raise ValueError(f"Invalid XAN release version: {version!r}")
    parts = version.split(".")
    marketing_version = ".".join(parts[:3])
    build_number = parts[3] if len(parts) == 4 else properties["XAN_BUILD_NUMBER"]
    if not build_number.isdecimal() or int(build_number) < 1:
        raise ValueError("XAN_BUILD_NUMBER must be a positive integer")
    if marketing_version != properties["XAN_VERSION"] or build_number != properties["XAN_BUILD_NUMBER"]:
        raise ValueError(
            f"iOS release {marketing_version} ({build_number}) must match Android's "
            f"XAN_VERSION {properties['XAN_VERSION']} and XAN_BUILD_NUMBER {properties['XAN_BUILD_NUMBER']}"
        )
    source = root / "iosApp/project.yml"
    spec = source.read_text(encoding="utf-8")
    for key, value in (("MARKETING_VERSION", marketing_version), ("CURRENT_PROJECT_VERSION", build_number)):
        spec, count = re.subn(rf"(?m)^(\s+{key}:)\s*[^\r\n]+$", rf"\g<1> '{value}'", spec)
        if count != 1:
            raise ValueError(f"Expected one {key} setting in {source}")
    output = root / "iosApp/project.generated.yml"
    output.write_text(spec, encoding="utf-8", newline="\n")
    print(f"Generated {output.name}: version {marketing_version}, build {build_number}")
    return output


if __name__ == "__main__":
    generate_spec(Path(__file__).resolve().parent, os.environ.get("XAN_RELEASE_TAG", "").strip())
