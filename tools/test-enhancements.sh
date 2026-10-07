#!/bin/sh
set -eu
ROOT="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
OUT="$ROOT/artifacts/tests"
REPORT="$ROOT/reports/current"
CP="$ROOT/artifacts/build/classes:$ROOT/tools/android-35.jar"
mkdir -p "$OUT/classes" "$OUT/pdf" "$OUT/review"
javac -encoding UTF-8 --add-modules jdk.httpserver -classpath "$CP" -d "$OUT/classes" \
    tests/PdfRegression.java tests/PreservationRegression.java tests/ReviewRegression.java tests/ApiRegression.java
CP="$OUT/classes:$CP"
java -classpath "$CP" com.rikkahub.wordlite.PdfRegression "$OUT/pdf/metadata.pdf" > "$REPORT/pdf-metadata.log"
java -classpath "$CP" com.rikkahub.wordlite.PreservationRegression tests/samples/complex-preservation.docx > "$REPORT/ooxml-preservation.log"
java -classpath "$CP" com.rikkahub.wordlite.ReviewRegression tests/fixture.docx "$OUT/review/threaded-revisions.docx" > "$REPORT/review-regression.log"
java --add-modules jdk.httpserver -classpath "$CP" com.rikkahub.wordlite.ApiRegression > "$REPORT/api-regression.log"
for name in pdf-metadata ooxml-preservation review-regression api-regression; do tail -1 "$REPORT/$name.log"; done
if [ -d /workspace/test-deps/fonttools ]; then
    PYTHONPATH=/workspace/test-deps/fonttools python3 tests/font_subset_test.py > "$REPORT/font-subsets-test.log"
    tail -1 "$REPORT/font-subsets-test.log"
else echo 'SKIP FontTools coverage check: tool unavailable' >&2; fi
