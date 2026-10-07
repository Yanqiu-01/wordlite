#!/bin/sh
set -eu
ROOT="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
JAR="${AAPT2_JAR:-/workspace/test-deps/aapt2-8.13.2-linux.jar}"
BIN="$ROOT/artifacts/host-tools/aapt2"
if [ ! -f "$JAR" ]; then
  JAR="$(find /root/.gradle/caches/modules-2/files-2.1/com.android.tools.build/aapt2 -name '*linux.jar' | head -1)"
fi
if [ ! -x "$BIN" ]; then
  mkdir -p "$(dirname "$BIN")"
  unzip -p "$JAR" aapt2 > "$BIN"
  chmod +x "$BIN"
fi
if [ "$(uname -m)" = aarch64 ]; then
  exec qemu-x86_64 -cpu max "$BIN" "$@"
fi
exec "$BIN" "$@"
