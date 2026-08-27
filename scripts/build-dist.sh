#!/usr/bin/env bash
# Build single-file CLIs for darwin-arm64 and linux-amd64 (Go launcher + embedded fat jar).
# Author: kejiqing
#
# Env:
#   REGION=china  → Chinese Go mirror (default)
#   REGION=other  → https://go.dev/dl
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

GO_VERSION="${GO_VERSION:-1.22.10}"
REGION="${REGION:-china}"
DIST="$ROOT/dist"
EMBED_DIR="$ROOT/cmd/doris-sql-err-check/embed"
mkdir -p "$DIST" "$EMBED_DIR"

echo "==> 1) fat jar"
JAR="$ROOT/target/doris-sql-err-check-0.1.0-SNAPSHOT.jar"
if [[ "${SKIP_JAR_BUILD:-0}" == "1" && -f "$JAR" ]]; then
  echo "SKIP_JAR_BUILD=1, reuse $JAR"
else
  "$ROOT/scripts/build.sh"
fi

if [[ ! -f "$JAR" ]]; then
  echo "missing $JAR" >&2
  exit 1
fi
cp -f "$JAR" "$EMBED_DIR/app.jar"
echo "embedded $(wc -c < "$EMBED_DIR/app.jar") bytes -> cmd/doris-sql-err-check/embed/app.jar"

echo "==> 2) ensure Go toolchain"
GO_BIN=""
if command -v go >/dev/null 2>&1; then
  GO_BIN="$(command -v go)"
else
  OS="$(uname -s | tr '[:upper:]' '[:lower:]')"
  ARCH="$(uname -m)"
  case "$ARCH" in
    x86_64) ARCH=amd64 ;;
    aarch64|arm64) ARCH=arm64 ;;
  esac
  GO_TGZ="go${GO_VERSION}.${OS}-${ARCH}.tar.gz"
  if [[ "${REGION}" == "china" ]]; then
    GO_URL="https://mirrors.aliyun.com/golang/${GO_TGZ}"
  else
    GO_URL="https://go.dev/dl/${GO_TGZ}"
  fi
  GO_HOME="$ROOT/.tools/go"
  if [[ ! -x "$GO_HOME/bin/go" ]]; then
    echo "downloading $GO_URL (REGION=$REGION)"
    mkdir -p "$ROOT/.tools"
    rm -rf "$GO_HOME"
    TMP_TGZ="$ROOT/.tools/${GO_TGZ}"
    rm -f "$TMP_TGZ"
    curl -fL --retry 3 --retry-delay 2 -o "$TMP_TGZ" "$GO_URL"
    SZ=$(wc -c < "$TMP_TGZ" | tr -d ' ')
    if [[ "$SZ" -lt 40000000 ]]; then
      echo "Go tarball too small ($SZ bytes); download likely incomplete" >&2
      exit 1
    fi
    tar -C "$ROOT/.tools" -xzf "$TMP_TGZ"
  fi
  GO_BIN="$GO_HOME/bin/go"
fi
echo "using $($GO_BIN version)"

build_one() {
  local goos="$1" goarch="$2" out="$3"
  echo "==> build $out (GOOS=$goos GOARCH=$goarch)"
  (
    cd "$ROOT"
    CGO_ENABLED=0 GOOS="$goos" GOARCH="$goarch" \
      "$GO_BIN" build -trimpath -ldflags="-s -w" \
      -o "$DIST/$out" ./cmd/doris-sql-err-check
  )
  chmod +x "$DIST/$out"
  ls -lh "$DIST/$out"
  file "$DIST/$out" || true
}

build_one darwin arm64 doris-sql-err-check-darwin-arm64
build_one linux amd64 doris-sql-err-check-linux-amd64

if [[ "$(uname -s)" == "Darwin" && "$(uname -m)" == "arm64" ]]; then
  cp -f "$DIST/doris-sql-err-check-darwin-arm64" "$DIST/doris-sql-err-check"
  chmod +x "$DIST/doris-sql-err-check"
fi

(
  cd "$DIST"
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 doris-sql-err-check-darwin-arm64 doris-sql-err-check-linux-amd64 > SHA256SUMS
  else
    sha256sum doris-sql-err-check-darwin-arm64 doris-sql-err-check-linux-amd64 > SHA256SUMS
  fi
)

echo ""
echo "Done. Single-file CLIs:"
echo "  $DIST/doris-sql-err-check-darwin-arm64   # macOS Apple Silicon"
echo "  $DIST/doris-sql-err-check-linux-amd64    # Linux x86_64"
echo ""
echo "Target machine still needs JDK 17+ (JAVA_HOME or java on PATH)."
echo "Usage: ./doris-sql-err-check-linux-amd64 --json --sql-text '...' --error-message '...'"
