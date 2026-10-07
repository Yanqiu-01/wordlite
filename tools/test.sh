#!/bin/sh
set -eu
ROOT="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
OUT="$ROOT/artifacts/tests"
REPORT="$ROOT/reports/current"
CLASSPATH="$ROOT/artifacts/build/classes:$ROOT/tools/android-35.jar"
REAL_DOCX="${1:-$ROOT/tests/samples/input-liu.docx}"
if [ ! -d "$ROOT/artifacts/build/classes" ]; then
  echo "Run sh tools/build.sh first" >&2
  exit 1
fi
mkdir -p "$OUT/classes" "$OUT/roundtrip" "$OUT/original" "$REPORT"
javac -encoding UTF-8 -classpath "$CLASSPATH" -d "$OUT/classes" \
  tests/Regression.java tests/OriginalDocxRegression.java tests/TableGeometryRegression.java \
  tests/FontAssetsRegression.java
CLASSPATH="$OUT/classes:$CLASSPATH"
java -classpath "$CLASSPATH" com.rikkahub.wordlite.Regression \
  "$ROOT/tests/fixture.docx" "$OUT/roundtrip" > "$REPORT/regression.log"
tail -1 "$REPORT/regression.log"
java -Djava.awt.headless=true -classpath "$CLASSPATH" com.rikkahub.wordlite.FontAssetsRegression \
  "$ROOT" "$ROOT/artifacts/apk/wordlite-debug.apk" > "$REPORT/fonts.log"
tail -1 "$REPORT/fonts.log"
if [ -f "$REAL_DOCX" ]; then
  java -classpath "$CLASSPATH" com.rikkahub.wordlite.OriginalDocxRegression \
    "$REAL_DOCX" "$OUT/original/roundtrip.docx" > "$REPORT/original-docx.log"
  tail -1 "$REPORT/original-docx.log"
  java -classpath "$CLASSPATH" com.rikkahub.wordlite.TableGeometryRegression \
    "$REAL_DOCX" "$OUT/original/table-geometry.docx" "$ROOT/tests/fixture.docx" > "$REPORT/table-geometry.log"
  tail -1 "$REPORT/table-geometry.log"
else
  echo "SKIP real-document test: $REAL_DOCX not found" >&2
fi
