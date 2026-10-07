#!/bin/sh
# Optional x86_64 native graphics probe under QEMU on the ARM build host.
set -eu
ROOT="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
JAVA="${NATIVE_JAVA:-/workspace/test-deps/jre-x86/usr/lib/jvm/java-17-openjdk-amd64/bin/java}"
GRADLE="${GRADLE:-/root/.gradle/wrapper/dists/gradle-8.14.2-bin/2pb3mgt1p815evrl3weanttgr/gradle-8.14.2/bin/gradle}"
if [ ! -x "$JAVA" ]; then echo 'Native JRE unavailable; this test is skipped' >&2; exit 2; fi
cd "$ROOT/tests/ui"
env JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64 "$GRADLE" --offline --no-daemon \
  testClasses printTestClasspath --console=plain > "$ROOT/artifacts/analysis/test-classpath.log" 2>&1
CP="$(grep '^/workspace/.*\.jar' "$ROOT/artifacts/analysis/test-classpath.log" | tail -1)"
cd "$ROOT"
exec qemu-x86_64 -cpu max "$JAVA" -Xmx768m -XX:TieredStopAtLevel=1 \
  -Dapp.root="$ROOT" -Drobolectric.dependency.repo.url=https://repo.maven.apache.org/maven2 \
  -classpath "$CP" org.junit.runner.JUnitCore "${1:-com.rikkahub.wordlite.PdfNativeTest}"
