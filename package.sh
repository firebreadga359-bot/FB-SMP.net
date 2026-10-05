#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VERSION="${RELEASE_VERSION:-1.0.0}"
DIST_DIR="${DIST_DIR:-$ROOT/dist}"
TMP_DIR="$(mktemp -d)"
STAGE="$TMP_DIR/FB-SMP"
ZIP_FILE="$DIST_DIR/fbsmp-$VERSION.zip"

cleanup() {
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

command -v zip >/dev/null 2>&1 || {
  printf 'The zip command is required to create the release archive.\n' >&2
  exit 1
}

cd "$ROOT"
./gradlew clean test assembleServer

mkdir -p "$STAGE/gradle" "$STAGE/server" "$STAGE/fbsmp-common" \
  "$STAGE/fbsmp-paper" "$STAGE/fbsmp-velocity" "$DIST_DIR"
cp README.md build.gradle.kts settings.gradle.kts gradle.properties gradlew gradlew.bat .gitignore \
  start.sh stop.sh backup.sh package.sh "$STAGE/"
cp -R gradle/wrapper "$STAGE/gradle/"

for module in fbsmp-common fbsmp-paper fbsmp-velocity; do
  cp -R "$module/src" "$STAGE/$module/"
  cp "$module/build.gradle.kts" "$STAGE/$module/"
  mkdir -p "$STAGE/$module/build/libs"
  find "$module/build/libs" -maxdepth 1 -type f -name '*.jar' -exec cp {} "$STAGE/$module/build/libs/" \;
done

for config in geyser-config.yml paper-global.yml server.properties velocity.toml; do
  cp "$ROOT/server/$config" "$STAGE/server/$config"
done

rm -f "$ZIP_FILE"
(cd "$TMP_DIR" && zip -qr "$ZIP_FILE" FB-SMP)
zip -T "$ZIP_FILE" >/dev/null
printf 'Release archive: %s\n' "$ZIP_FILE"
sha256sum "$ZIP_FILE"