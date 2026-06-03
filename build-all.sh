#!/usr/bin/env bash
# build-all.sh
#
# Bootstraps all dependencies and builds S202 from scratch.
#
# What it does:
#   1. Checks for git, Java 21+, and Maven
#   2. Installs WFX into the local Maven cache (~/.m2) if not already present
#      (clones https://github.com/Weigend/wfx and runs mvn install -DskipTests)
#   3. Builds S202
#
# Usage:
#   chmod +x build-all.sh
#   ./build-all.sh
#
# Requirements: bash, git, java 21+, maven 3.9+
#
# Windows: use WSL or Git Bash — native cmd/PowerShell is not supported.

set -euo pipefail

WFX_REPO="https://github.com/Weigend/wfx.git"
WFX_VERSION="1.0.1"
WFX_JAR="$HOME/.m2/repository/io/softwareecg/wfx/wfx-platform/${WFX_VERSION}/wfx-platform-${WFX_VERSION}.jar"

S202_DIR="$(cd "$(dirname "$0")" && pwd)"

# ── helpers ──────────────────────────────────────────────────────────────────

info()  { echo "  [build-all] $*"; }
error() { echo; echo "  [build-all] ERROR: $*" >&2; exit 1; }

check_cmd() {
    command -v "$1" >/dev/null 2>&1 || error "'$1' not found. $2"
}

check_java() {
    check_cmd java "Install Java 21+ and make sure it is on PATH."
    local raw ver
    raw=$(java -version 2>&1 | head -1)
    # handles: 'java version "21.0.1"', 'openjdk version "21"', etc.
    ver=$(echo "$raw" | sed 's/[^"]*"\([0-9]*\).*/\1/')
    if ! [[ "$ver" =~ ^[0-9]+$ ]]; then
        error "Could not parse Java version from: $raw"
    fi
    if [ "$ver" -lt 21 ]; then
        error "Java 21+ required. Detected version $ver from: $raw"
    fi
    info "Java $ver  OK"
}

check_mvn() {
    check_cmd mvn "Install Maven 3.9+ (https://maven.apache.org/download.cgi)."
    local raw major minor
    raw=$(mvn --version 2>/dev/null | head -1 | awk '{print $3}')
    major=$(echo "$raw" | cut -d. -f1)
    minor=$(echo "$raw" | cut -d. -f2)
    if ! [[ "$major" =~ ^[0-9]+$ && "$minor" =~ ^[0-9]+$ ]]; then
        error "Could not parse Maven version from: $raw"
    fi
    if [ "$major" -lt 3 ] || { [ "$major" -eq 3 ] && [ "$minor" -lt 9 ]; }; then
        error "Maven 3.9+ required. Detected version $raw"
    fi
    info "Maven $raw  OK"
}

# ── main ─────────────────────────────────────────────────────────────────────

echo ""
echo "========================================"
echo "  S202 build-all"
echo "========================================"
echo ""

check_cmd git "Install git (https://git-scm.com)."
check_java
check_mvn

# ── Step 1: install WFX if not already cached ────────────────────────────────

if [ -f "$WFX_JAR" ]; then
    info "WFX ${WFX_VERSION} already in local Maven cache — skipping."
else
    info "WFX ${WFX_VERSION} not found in ~/.m2 — cloning and building …"
    info "(this happens only once)"
    echo ""

    WFX_TMP="$(mktemp -d)"

    git clone --depth 1 "$WFX_REPO" "$WFX_TMP/wfx" \
        || { rm -rf "$WFX_TMP"; error "git clone of WFX failed. Check network / proxy settings."; }

    (
        cd "$WFX_TMP/wfx"
        mvn install -DskipTests \
            || { cd /; rm -rf "$WFX_TMP"; error "WFX build failed. See Maven output above."; }
    )

    rm -rf "$WFX_TMP"
    echo ""
    info "WFX installed successfully."
fi

# ── Step 2: build S202 ───────────────────────────────────────────────────────

echo ""
info "Building S202 …"
echo ""

(
    cd "$S202_DIR"
    mvn clean install \
        || error "S202 build failed. See Maven output above."
)

echo ""
echo "========================================"
echo "  Build successful!"
echo ""
echo "  Run the application:"
echo "    mvn javafx:run -pl analyzer"
echo "========================================"
echo ""
