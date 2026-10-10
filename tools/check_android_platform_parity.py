#!/usr/bin/env python3
"""Report Android changes and verify the platform ports stay aligned."""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]

ANDROID_LOGOS = {
    "white": "mobile/android/app/src/main/res/drawable-nodpi/xan_launcher_white_mark.png",
    "gold": "mobile/android/app/src/main/res/drawable-nodpi/xan_gold_mark.png",
    "horizon": "mobile/android/branding/hotswap/android/res-packaged/drawable-nodpi/xan_mark.png",
    "aurora": "mobile/android/app/src/main/res/drawable-nodpi/xan_logo_aurora_mark.png",
    "sunset": "mobile/android/app/src/main/res/drawable-nodpi/xan_logo_sunset_mark.png",
    "green": "mobile/android/app/src/main/res/drawable-nodpi/xan_logo_green_mark.png",
    "prism": "mobile/android/app/src/main/res/drawable-nodpi/xan_logo_prism_mark.png",
    "lilac": "mobile/android/app/src/main/res/drawable-nodpi/xan_logo_lilac_mark.png",
    "blue": "mobile/android/app/src/main/res/drawable-nodpi/xan_logo_blue_mark.png",
}

IGNORED_ANDROID_SEGMENTS = {".gradle", ".idea", "build", "captures"}


def parse_logo_entries(source: str) -> list[tuple[str, str]]:
    """Return enum name and preference key pairs from an AppLogo source file."""
    return re.findall(r'^\s*([A-Z][A-Z0-9_]*)\("([a-z0-9_-]+)"', source, re.MULTILINE)


def logo_catalogs() -> dict[str, list[tuple[str, str]]]:
    sources = {
        "Android": "mobile/android/app/src/main/kotlin/app/xan/music/constants/AppLogo.kt",
        "Desktop": "desktop/shared/src/main/kotlin/app/xan/desktop/DesktopBranding.kt",
        "iOS": "mobile/ios/shared/src/commonMain/kotlin/app/xan/shared/IosAppLogo.kt",
    }
    return {name: parse_logo_entries((ROOT / path).read_text(encoding="utf-8")) for name, path in sources.items()}


def sync_android_logo_assets() -> list[str]:
    """Copy Android's current logo marks into the desktop and iOS app resources."""
    changed: list[str] = []
    target_dirs = {
        "Desktop": ROOT / "desktop/shared/src/main/resources/brand",
        "iOS": ROOT / "mobile/ios/shared/src/commonMain/composeResources/drawable",
    }
    for variant, source_path in ANDROID_LOGOS.items():
        source = ROOT / source_path
        if not source.is_file():
            raise FileNotFoundError(f"Android logo source is missing: {source_path}")
        for platform, target_dir in target_dirs.items():
            target = target_dir / f"xan_logo_{variant}.png"
            target_dir.mkdir(parents=True, exist_ok=True)
            data = source.read_bytes()
            if not target.exists() or target.read_bytes() != data:
                target.write_bytes(data)
                changed.append(f"{platform}: {target.relative_to(ROOT).as_posix()}")

    gold = (ROOT / ANDROID_LOGOS["gold"]).read_bytes()
    for target in (
        ROOT / "desktop/shared/src/main/resources/brand/xan_gold.png",
        ROOT / "mobile/ios/shared/src/commonMain/composeResources/drawable/xan_gold.png",
        ROOT / "desktop/shared/src/main/resources/brand/xan_android.png",
        ROOT / "mobile/ios/shared/src/commonMain/composeResources/drawable/xan_android.png",
    ):
        if not target.exists() or target.read_bytes() != gold:
            target.write_bytes(gold)
            changed.append(f"shared mark: {target.relative_to(ROOT).as_posix()}")
    return changed


def validate_logo_catalogs() -> list[str]:
    catalogs = logo_catalogs()
    reference = catalogs["Android"]
    errors = []
    for platform, entries in catalogs.items():
        if entries != reference:
            missing = [key for key in reference if key not in entries]
            extra = [key for key in entries if key not in reference]
            errors.append(
                f"{platform} logo choices differ from Android "
                f"(missing={missing or 'none'}, extra={extra or 'none'})."
            )
    return errors


def is_relevant_android_path(path: str) -> bool:
    if not path.startswith("mobile/android/"):
        return False
    return not any(segment in IGNORED_ANDROID_SEGMENTS for segment in Path(path).parts)


def classify_android_paths(paths: list[str]) -> dict[str, list[str]]:
    groups: dict[str, list[str]] = {
        "Application behavior and UI": [],
        "Provider modules and integrations": [],
        "Branding and app resources": [],
        "Build, dependencies, and release metadata": [],
        "Listen Together protocol and services": [],
        "Documentation and other": [],
    }
    for path in paths:
        if not is_relevant_android_path(path):
            continue
        if "/app/src/main/kotlin/" in path and "/listentogether/" in path:
            group = "Listen Together protocol and services"
        elif path.endswith("listen-together.properties") or path.startswith("mobile/android/backend/"):
            group = "Listen Together protocol and services"
        elif "/modules/" in path:
            group = "Provider modules and integrations"
        elif "/app/src/main/kotlin/" in path:
            group = "Application behavior and UI"
        elif "/app/src/main/res/" in path or "/branding/" in path or "/app/src/main/assets/" in path:
            group = "Branding and app resources"
        elif path.endswith((".gradle", ".gradle.kts", ".toml")) or path.endswith(("gradle.properties", "version.properties")):
            group = "Build, dependencies, and release metadata"
        elif path.startswith(("mobile/android/gradle/", "mobile/android/fastlane/")):
            group = "Build, dependencies, and release metadata"
        elif path.endswith((".md", ".txt")):
            group = "Documentation and other"
        else:
            group = "Application behavior and UI"
        groups[group].append(path)
    return {name: items for name, items in groups.items() if items}


def git_changed_paths(base: str, head: str, event: str) -> list[str]:
    if not head:
        head = "HEAD"
    if not base or set(base) == {"0"}:
        base = f"{head}^"
    separator = "..." if event == "pull_request" else ".."
    result = subprocess.run(
        ["git", "diff", "--name-only", "-z", "--diff-filter=ACMRD", f"{base}{separator}{head}"],
        cwd=ROOT,
        check=True,
        stdout=subprocess.PIPE,
        text=False,
    )
    return [p.decode("utf-8", errors="replace") for p in result.stdout.split(b"\0") if p]


def android_version() -> tuple[str, str]:
    properties = {}
    for line in (ROOT / "mobile/android/version.properties").read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if stripped and not stripped.startswith("#") and "=" in stripped:
            key, value = stripped.split("=", 1)
            properties[key.strip()] = value.strip()
    return properties.get("XAN_VERSION", "unknown"), properties.get("XAN_VERSION_CODE", "unknown")


def parity_report(paths: list[str], base: str, head: str, event: str) -> str:
    android_paths = [path for path in paths if is_relevant_android_path(path)]
    groups = classify_android_paths(android_paths)
    desktop_paths = [path for path in paths if path.startswith("desktop/")]
    ios_paths = [path for path in paths if path.startswith("mobile/ios/")]
    version, version_code = android_version()
    catalogs = logo_catalogs()
    errors = validate_logo_catalogs()

    lines = [
        "## Android → desktop/iOS update report",
        "",
        f"Android source version: **{version}** (version code `{version_code}`).",
        f"Compared `{base or 'previous commit'}` → `{head or 'HEAD'}` ({event}).",
        "",
    ]
    if android_paths:
        lines.append(f"Detected **{len(android_paths)} Android file change(s)**:")
        for group, items in groups.items():
            lines.append(f"- **{group}:** {len(items)}")
        lines.extend(["", "Changed Android paths:"])
        lines.extend(f"- `{path}`" for path in android_paths[:60])
        if len(android_paths) > 60:
            lines.append(f"- … and {len(android_paths) - 60} more")
    else:
        lines.append("No Android source or resource changes were found in this comparison.")

    lines.extend(
        [
            "",
            f"Desktop files changed: **{len(desktop_paths)}**; iOS files changed: **{len(ios_paths)}**.",
            "Desktop CI and iOS CI run for changes anywhere under `mobile/android/`. Both builds consume Android's release version and Listen Together protocol; the build sync also copies the current Android logo catalog into desktop and iOS resources.",
        ]
    )
    behavioral = any(
        path.startswith("mobile/android/modules/")
        or "/app/src/main/kotlin/" in path
        or "/app/src/main/java/" in path
        for path in android_paths
    )
    if behavioral and (not desktop_paths or not ios_paths):
        missing = []
        if not desktop_paths:
            missing.append("desktop")
        if not ios_paths:
            missing.append("iOS/iPadOS")
        lines.extend(
            [
                "",
                f"> **Parity review:** Android behavior/provider code changed without matching changes under `{', '.join(missing)}`. Review whether these platform ports need the same behavior; this report cannot translate Android-specific code automatically.",
            ]
        )

    lines.extend(["", "Logo catalog in this build:"])
    for platform, entries in catalogs.items():
        lines.append(f"- {platform}: {', '.join(key for _, key in entries) or '(none)'}")
    if errors:
        lines.extend(["", "**Catalog errors:**"])
        lines.extend(f"- {error}" for error in errors)
    else:
        lines.extend(["", "Logo choices match across Android, desktop, and iOS."])
    lines.extend(
        [
            "",
            "Version, room protocol, and branding have build-time Android sources. Feature behavior still requires a deliberate desktop/iOS implementation and is checked by the Desktop CI and iOS CI workflows triggered for this change.",
        ]
    )
    return "\n".join(lines) + "\n"


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sync-assets", action="store_true", help="copy current Android logo marks into desktop and iOS resource folders")
    parser.add_argument("--sync-assets-only", action="store_true", help="sync logos and exit without generating a change report")
    parser.add_argument("--base", default=os.environ.get("ANDROID_PARITY_BASE", ""))
    parser.add_argument("--head", default=os.environ.get("ANDROID_PARITY_HEAD", "HEAD"))
    parser.add_argument("--event", default=os.environ.get("GITHUB_EVENT_NAME", "workflow_dispatch"))
    args = parser.parse_args()

    if args.sync_assets or args.sync_assets_only:
        changed = sync_android_logo_assets()
        print("Synced Android logo assets:" if changed else "Android logo assets were already current.")
        for path in changed:
            print(f"- {path}")
    if args.sync_assets_only:
        return 0

    errors = validate_logo_catalogs()
    if errors:
        for error in errors:
            print(error, file=sys.stderr)
        return 1

    paths = git_changed_paths(args.base, args.head, args.event)
    report = parity_report(paths, args.base, args.head, args.event)
    print(report)
    if "**Parity review:**" in report:
        print("::warning title=Android platform parity review::Android behavior or provider code changed without matching desktop/iOS source changes.")
    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary_path:
        with open(summary_path, "a", encoding="utf-8") as summary:
            summary.write(report)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
