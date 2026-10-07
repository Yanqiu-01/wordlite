#!/bin/sh
set -eu
ROOT="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
VERSION="$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' app/src/main/AndroidManifest.xml)"
DEST="$ROOT/releases/$VERSION"
if [ -e "$DEST/wordlite-$VERSION.apk" ]; then
  echo "Release already archived: $VERSION" >&2
  exit 1
fi
mkdir -p "$DEST"
cp artifacts/apk/wordlite-debug.apk "$DEST/wordlite-$VERSION.apk"
cp -R reports/current "$DEST/reports"
tar -czf "$DEST/source.tar.gz" app/src/main tools tests/*.java tests/*.py tests/ui/src \
  tests/ui/build.gradle tests/ui/settings.gradle tests/fixture.docx \
  tests/samples/input-liu.docx tests/samples/complex-preservation.docx README.md .gitignore
python3 - "$DEST" <<'PY'
import hashlib, json, pathlib, sys
root = pathlib.Path.cwd()
assets = root / 'app/src/main/assets'
manifest = {str(path.relative_to(root)): hashlib.sha256(path.read_bytes()).hexdigest()
            for path in sorted(assets.rglob('*')) if path.is_file()}
(pathlib.Path(sys.argv[1]) / 'asset-sha256.json').write_text(json.dumps(manifest, indent=2) + '\n')
PY
sha256sum "$DEST/wordlite-$VERSION.apk" "$DEST/source.tar.gz" > "$DEST/SHA256SUMS"
cp artifacts/apk/wordlite-debug.apk /workspace/wordlite-debug.apk
printf 'Archived %s\n' "$DEST"
