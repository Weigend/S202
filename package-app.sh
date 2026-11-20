#!/usr/bin/env bash
# package-app.sh
#
# Packages S202 as a self-contained program for the operating system this script
# runs on. The result needs no Java, Maven or Node.js on the target machine: it
# carries its own Java runtime (with JavaFX), all jars and the City3D web bundle.
#
#   Linux    analyzer/target/package/S202-<version>-linux-<arch>.tar.gz    (S202/bin/S202)
#   macOS    analyzer/target/package/S202-<version>-macos-<arch>.zip       (S202.app)
#   Windows  analyzer/target/package/S202-<version>-windows-<arch>.zip     (S202\S202.exe)
#
# jpackage cannot cross-build, so each archive has to be built on its own OS.
# .github/workflows/package.yml does that for all of them.
#
# Usage:
#   ./build-all.sh              # once: WFX, S202 and the City3D bundle
#   ./package-app.sh            # package
#   ./package-app.sh --smoke    # package, then start the program once to prove it launches
#
# Requirements: bash, Maven, and a JDK 25+ that ships the JavaFX jmods, for example
# Liberica "Full JDK" (the one .sdkmanrc names: sdk env install) or Azul Zulu FX.
# With a plain JDK, download the JavaFX jmods from https://gluonhq.com/products/javafx/
# and point JAVAFX_JMODS at the unpacked directory.
#
# Windows: run it from Git Bash.

set -euo pipefail

APP_NAME="S202"
MAIN_CLASS="de.weigend.s202.ui.shell.S202Main"

# java.* / jdk.* modules as reported by jdeps for the analyzer jar and its
# dependencies, plus the ones that are only reached reflectively (logging, XML,
# JMX for logback; sun.misc.Unsafe users; zip file systems; extra charsets for
# source files; TLS). javafx.web pulls in controls, graphics, base and media.
RUNTIME_MODULES="java.base,java.desktop,java.logging,java.management,java.naming,java.prefs,java.sql,java.xml"
RUNTIME_MODULES="$RUNTIME_MODULES,jdk.charsets,jdk.crypto.ec,jdk.httpserver,jdk.unsupported,jdk.zipfs"
RUNTIME_MODULES="$RUNTIME_MODULES,javafx.controls,javafx.fxml,javafx.web"

SMOKE=false
for arg in "$@"; do
    case "$arg" in
        --smoke) SMOKE=true ;;
        *) echo "Unknown option: $arg" >&2; exit 2 ;;
    esac
done

# Everything below uses paths relative to the project root. That keeps Git Bash
# on Windows from having to translate absolute POSIX paths for native tools.
cd "$(dirname "$0")"

info()  { echo "  [package-app] $*"; }
error() { echo; echo "  [package-app] ERROR: $*" >&2; exit 1; }

# ── platform ─────────────────────────────────────────────────────────────────

case "$(uname -s)" in
    Linux*)               OS=linux ;;
    Darwin*)              OS=macos ;;
    MINGW*|MSYS*|CYGWIN*) OS=windows ;;
    *) error "Unsupported operating system: $(uname -s)" ;;
esac

case "$(uname -m)" in
    x86_64|amd64)  ARCH=x64 ;;
    arm64|aarch64) ARCH=arm64 ;;
    *) error "Unsupported CPU architecture: $(uname -m)" ;;
esac

# ── inputs ───────────────────────────────────────────────────────────────────

VERSION="$(sed -n 's:.*<version>\(.*\)</version>.*:\1:p' pom.xml | head -1)"
[ -n "$VERSION" ] || error "Could not read the project version from pom.xml."
# jpackage (macOS in particular) accepts only up to three plain numbers.
APP_VERSION="$(echo "$VERSION" | sed 's/[^0-9.].*$//')"

MAIN_JAR="s202-code-analyzer-${VERSION}.jar"
[ -f "analyzer/target/$MAIN_JAR" ] \
    || error "analyzer/target/$MAIN_JAR not found. Run ./build-all.sh first."
[ -f "city3d/dist/index.html" ] \
    || error "city3d/dist not found. Run ./build-all.sh with Node.js 18+ installed (or: cd city3d && npm install && npm run build)."

command -v jpackage >/dev/null 2>&1 || error "'jpackage' not found. Put the bin directory of a JDK 25+ on PATH."
# The bundled runtime is cut from this JDK and has to run classes compiled for Java 25.
JAVA_MAJOR="$(java -version 2>&1 | head -1 | sed 's/[^"]*"\([0-9]*\).*/\1/')"
case "$JAVA_MAJOR" in
    ''|*[!0-9]*) error "Could not read the Java version from: $(java -version 2>&1 | head -1)" ;;
esac
[ "$JAVA_MAJOR" -ge 25 ] || error "JDK 25+ required for packaging. Found Java $JAVA_MAJOR."
command -v mvn >/dev/null 2>&1      || error "'mvn' not found. Install Maven 3.9+."

MODULE_PATH_ARGS=()
if [ -n "${JAVAFX_JMODS:-}" ]; then
    [ -d "$JAVAFX_JMODS" ] || error "JAVAFX_JMODS points to '$JAVAFX_JMODS', which is not a directory."
    MODULE_PATH_ARGS=(--module-path "$JAVAFX_JMODS")
    info "JavaFX jmods from $JAVAFX_JMODS"
else
    JDK_MODULES="$(java --list-modules)"
    case "$JDK_MODULES" in
        *javafx.web@*) ;;
        *) error "This JDK has no JavaFX modules. Use a JDK that ships them (Liberica Full JDK, Azul Zulu FX) or set JAVAFX_JMODS." ;;
    esac
fi

info "S202 $VERSION for $OS-$ARCH"

# ── stage the application files ──────────────────────────────────────────────

WORK="analyzer/target/package"
INPUT="$WORK/input"
IMAGE="$WORK/image"
rm -rf "$WORK"
mkdir -p "$INPUT" "$IMAGE"

# Runtime jars. JavaFX is left out on purpose: it becomes part of the bundled
# Java runtime, where its native libraries sit ready to load. As jars on the
# classpath they would have to be unpacked into the user's home at first start.
info "Collecting runtime dependencies …"
rm -rf analyzer/target/dependency
mvn --batch-mode -q -pl analyzer dependency:copy-dependencies \
    -DincludeScope=runtime -DexcludeGroupIds=org.openjfx \
    || error "Could not collect the runtime dependencies. Run ./build-all.sh first."

cp analyzer/target/dependency/*.jar "$INPUT/"
cp "analyzer/target/$MAIN_JAR" "$INPUT/"
cp LICENSE excluded-prefixes.txt "$INPUT/"
mkdir -p "$INPUT/city3d"
cp -R city3d/dist/. "$INPUT/city3d/"

# ── build the app image ──────────────────────────────────────────────────────

# $APPDIR is expanded by the launcher at start to the directory holding the
# staged files, so the program finds its data wherever the user unpacked it.
PLATFORM_ARGS=()
if [ "$OS" = macos ]; then
    PLATFORM_ARGS=(--mac-package-identifier de.weigend.s202)
fi

info "Running jpackage …"
jpackage \
    --type app-image \
    --name "$APP_NAME" \
    --app-version "$APP_VERSION" \
    --vendor "Weigend AM GmbH & Co.KG" \
    --description "S202 Code Analyzer" \
    --copyright "Copyright 2026 Weigend AM GmbH & Co.KG" \
    --input "$INPUT" \
    --main-jar "$MAIN_JAR" \
    --main-class "$MAIN_CLASS" \
    --add-modules "$RUNTIME_MODULES" \
    --java-options '-Ds202.city3d.dist=$APPDIR/city3d' \
    --java-options '-Ds202.excludedPrefixes=$APPDIR/excluded-prefixes.txt' \
    --java-options '--enable-native-access=javafx.graphics,javafx.web' \
    --dest "$IMAGE" \
    ${MODULE_PATH_ARGS[@]+"${MODULE_PATH_ARGS[@]}"} \
    ${PLATFORM_ARGS[@]+"${PLATFORM_ARGS[@]}"} \
    || error "jpackage failed. See its output above."

case "$OS" in
    linux)   APP_ROOT="$IMAGE/$APP_NAME";     APP_DATA="$APP_ROOT/lib/app";      LAUNCHER="$APP_ROOT/bin/$APP_NAME" ;;
    macos)   APP_ROOT="$IMAGE/$APP_NAME.app"; APP_DATA="$APP_ROOT/Contents/app"; LAUNCHER="$APP_ROOT/Contents/MacOS/$APP_NAME" ;;
    windows) APP_ROOT="$IMAGE/$APP_NAME";     APP_DATA="$APP_ROOT/app";          LAUNCHER="$APP_ROOT/$APP_NAME.exe" ;;
esac

[ -f "$LAUNCHER" ]                       || error "Launcher missing after jpackage: $LAUNCHER"
[ -f "$APP_DATA/city3d/index.html" ]     || error "City3D bundle missing in the app image."
[ -f "$APP_DATA/excluded-prefixes.txt" ] || error "excluded-prefixes.txt missing in the app image."

# Apple Silicon refuses to run code without a signature. jpackage signs the
# bundle ad hoc; make sure that signature is really there and intact.
if [ "$OS" = macos ]; then
    codesign --verify --deep --strict "$APP_ROOT" 2>/dev/null \
        || codesign --force --deep --sign - "$APP_ROOT" \
        || error "Could not sign the app bundle ad hoc."
fi

# ── smoke test ───────────────────────────────────────────────────────────────

# Starts the packaged program and expects it to still be running after a while.
# A broken package (missing module, missing native library, failing startup)
# makes the JVM exit within the first seconds.
smoke_test() {
    local wait_seconds=30
    local settings="$WORK/smoke-settings"
    local log="$WORK/smoke.log"
    mkdir -p "$settings"

    info "Smoke test: starting $LAUNCHER …"
    local runner=()
    if [ "$OS" = linux ] && [ -z "${DISPLAY:-}" ] && [ -z "${WAYLAND_DISPLAY:-}" ]; then
        command -v xvfb-run >/dev/null 2>&1 || error "No display and no xvfb-run for the smoke test."
        runner=(xvfb-run -a)
    fi

    # Own settings directory: the test must not touch the window layout the
    # user has saved under ~/.s202.
    JAVA_TOOL_OPTIONS="-Ds202.settings.dir=$settings" \
        ${runner[@]+"${runner[@]}"} "$LAUNCHER" >"$log" 2>&1 &
    local pid=$!

    local alive=true
    for _ in $(seq "$wait_seconds"); do
        sleep 1
        if ! kill -0 "$pid" 2>/dev/null; then
            alive=false
            break
        fi
    done

    if [ "$OS" = windows ]; then
        taskkill //F //T //IM "$APP_NAME.exe" >/dev/null 2>&1 || true
    else
        pkill -P "$pid" 2>/dev/null || true
        kill "$pid" 2>/dev/null || true
    fi
    wait "$pid" 2>/dev/null || true

    echo "  ---- program output: warnings, errors and the last lines ($log) ----"
    { grep -E " (WARN|ERROR) |Exception|Error:" "$log" | head -40; tail -n 15 "$log"; } | cut -c1-300 | sed 's/^/  | /' || true
    echo "  ------------------------"

    $alive || error "Smoke test failed: the program exited within ${wait_seconds}s."
    if grep -qE "Can not start application|Can not preload module|NoClassDefFoundError|ClassNotFoundException|UnsatisfiedLinkError|FindException" "$log"; then
        error "Smoke test failed: startup errors in the program output."
    fi
    info "Smoke test passed: still running after ${wait_seconds}s, no startup errors."
}

if $SMOKE; then
    smoke_test
fi

# ── archive ──────────────────────────────────────────────────────────────────

ARCHIVE_BASE="$APP_NAME-$VERSION-$OS-$ARCH"
case "$OS" in
    linux)
        ARCHIVE="$WORK/$ARCHIVE_BASE.tar.gz"
        tar -czf "$ARCHIVE" -C "$IMAGE" "$APP_NAME"
        ;;
    macos)
        # ditto keeps the symlinks, permissions and the signature of the .app intact.
        ARCHIVE="$WORK/$ARCHIVE_BASE.zip"
        ditto -c -k --sequesterRsrc --keepParent "$APP_ROOT" "$ARCHIVE"
        ;;
    windows)
        # 'jar' is the one zip tool every JDK brings along.
        ARCHIVE="$WORK/$ARCHIVE_BASE.zip"
        jar --create --no-manifest --file "$ARCHIVE" -C "$IMAGE" "$APP_NAME"
        ;;
esac

echo ""
echo "========================================"
echo "  Packaged: $ARCHIVE"
echo "========================================"
echo ""
