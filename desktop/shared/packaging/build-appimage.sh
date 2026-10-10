#!/usr/bin/env bash
set -euo pipefail

VERSION="${1:?usage: build-appimage.sh VERSION}"
[[ "$VERSION" =~ ^[0-9]+(\.[0-9]+){2,3}$ ]] || { echo "Invalid XAN version: $VERSION" >&2; exit 1; }
PROJECT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIST="$PROJECT/build/compose/binaries/main/app/XAN"
OUT="$PROJECT/build/compose/binaries/main/appimage"
APPDIR="$OUT/XAN.AppDir"
TOOL="$PROJECT/build/compose/binaries/main/appimagetool-x86_64.AppImage"
TOOL_URL="https://github.com/AppImage/appimagetool/releases/download/1.9.1/appimagetool-x86_64.AppImage"
TOOL_SHA256="ed4ce84f0d9caff66f50bcca6ff6f35aae54ce8135408b3fa33abfc3cb384eb0"
RUNTIME="$PROJECT/build/compose/binaries/main/appimage-runtime-x86_64"
RUNTIME_URL="https://github.com/AppImage/type2-runtime/releases/download/continuous/runtime-x86_64"
# Official runtime release commit 8f39b89e2ac31e1640b3d3f7e9a5108e6ce805fa.
RUNTIME_SHA256="156f4bdbde9c52d01814600013e0a273f0118dc2de98975f3c8c63427ec79074"

[[ -d "$DIST" ]] || { echo "Desktop distribution missing: $DIST" >&2; exit 1; }
[[ -x "$DIST/bin/XAN" ]] || { echo "Linux launcher missing: $DIST/bin/XAN" >&2; exit 1; }
mkdir -p "$OUT"

if [[ ! -f "$TOOL" ]]; then
  curl --fail --location --retry 3 --connect-timeout 20 --max-time 180 "$TOOL_URL" -o "$TOOL"
fi
printf '%s  %s\n' "$TOOL_SHA256" "$TOOL" | sha256sum --check --status || {
  rm -f "$TOOL"
  echo "appimagetool 1.9.1 checksum verification failed." >&2
  exit 1
}
chmod +x "$TOOL"

if [[ ! -f "$RUNTIME" ]]; then
  curl --fail --location --retry 3 --connect-timeout 20 --max-time 180 "$RUNTIME_URL" -o "$RUNTIME"
fi
printf '%s  %s\n' "$RUNTIME_SHA256" "$RUNTIME" | sha256sum --check --status || {
  rm -f "$RUNTIME"
  echo "Pinned AppImage runtime checksum verification failed." >&2
  exit 1
}

rm -rf "$APPDIR"
mkdir -p "$APPDIR/usr/lib/xan" "$APPDIR/usr/share/icons/hicolor/256x256/apps" "$APPDIR/usr/share/applications"
cp -a "$DIST/." "$APPDIR/usr/lib/xan/"
install -m 0644 "$PROJECT/../linux/branding/xan-icon-pack/desktop-app-icons/icon.png" "$APPDIR/xan_gold.png"
install -m 0644 "$PROJECT/../linux/branding/xan-icon-pack/desktop-app-icons/icon.png" "$APPDIR/usr/share/icons/hicolor/256x256/apps/xan_gold.png"
cat > "$APPDIR/AppRun" <<'APPRUN'
#!/usr/bin/env sh
HERE="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
exec "$HERE/usr/lib/xan/bin/XAN" "$@"
APPRUN
chmod 0755 "$APPDIR/AppRun"
cat > "$APPDIR/XAN.desktop" <<'DESKTOP'
[Desktop Entry]
Type=Application
Name=XAN
Comment=Your music. Your library. Your way.
Exec=AppRun
Icon=xan_gold
Terminal=false
Categories=AudioVideo;Audio;Player;
DESKTOP
install -m 0644 "$APPDIR/XAN.desktop" "$APPDIR/usr/share/applications/XAN.desktop"

OUTPUT="$OUT/XAN-$VERSION-linux-x64.AppImage"
rm -f "$OUTPUT"
ARCH=x86_64 "$TOOL" --runtime-file "$RUNTIME" "$APPDIR" "$OUTPUT"
chmod 0755 "$OUTPUT"
test -s "$OUTPUT"
echo "$OUTPUT"
