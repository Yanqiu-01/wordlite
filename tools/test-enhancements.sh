#!/bin/sh
set -eu
ROOT="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
OUT="$ROOT/artifacts/tests"
REPORT="$ROOT/reports/current"
CP="$ROOT/artifacts/build/classes:$ROOT/tools/android-35.jar"
mkdir -p "$OUT/classes" "$OUT/pdf" "$OUT/review" "$OUT/library"
javac -encoding UTF-8 --add-modules jdk.httpserver -classpath "$CP" -d "$OUT/classes" \
    tests/PdfRegression.java tests/PreservationRegression.java tests/ReviewRegression.java \
    tests/ApiRegression.java tests/ScriptRegression.java tests/TextCorpusRegression.java \
    tests/AigcRegression.java tests/LocalRewriteRegression.java tests/DetectRegression.java
    tests/CnkiSearchRegression.java tests/CnkiTouchRegression.java
CP="$OUT/classes:$CP"
run() {
    name="$1"; shift
    java --add-modules jdk.httpserver -classpath "$CP" "com.rikkahub.wordlite.$name" "$@" > "$REPORT/$name.log"
    tail -1 "$REPORT/$name.log"
}
run PdfRegression "$OUT/pdf/metadata.pdf"
run PreservationRegression tests/samples/complex-preservation.docx
run ReviewRegression tests/fixture.docx "$OUT/review/threaded-revisions.docx"
run ApiRegression
run ScriptRegression
run TextCorpusRegression
run AigcRegression
run LocalRewriteRegression
run DetectRegression
run CnkiSearchRegression
run CnkiTouchRegression
if [ -d /workspace/test-deps/fonttools ]; then
    PYTHONPATH=/workspace/test-deps/fonttools python3 tests/font_subset_test.py > "$REPORT/font-subsets-test.log"
    tail -1 "$REPORT/font-subsets-test.log"
else echo 'SKIP FontTools coverage check: tool unavailable' >&2; fi
