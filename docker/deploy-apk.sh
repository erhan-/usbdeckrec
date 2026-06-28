#!/usr/bin/env bash
#
# deploy-apk.sh
#
# Installs the USB DeckRec APK on an ADB-connected Android device.
# Optionally builds first if the APK doesn't exist.
# After installing, automatically shows filtered logcat output.
#
# Usage:
#   ./docker/deploy-apk.sh               # Deploy debug APK (build if missing)
#   ./docker/deploy-apk.sh release        # Deploy release APK
#   ./docker/deploy-apk.sh --build        # Force rebuild before deploy
#   ./docker/deploy-apk.sh --no-logcat    # Deploy without showing logs
#
# Prerequisites:
#   - adb (Android Debug Bridge) installed on host
#   - One Android device connected via USB with USB debugging enabled

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

BUILD_VARIANT="debug"
FORCE_BUILD=false
SHOW_LOGCAT=true

# Parse arguments
for arg in "$@"; do
    case "$arg" in
        release)   BUILD_VARIANT="release" ;;
        --build)   FORCE_BUILD=true ;;
        --no-logcat) SHOW_LOGCAT=false ;;
    esac
done

if [ "$BUILD_VARIANT" = "release" ]; then
    APK_PATH="$PROJECT_DIR/app/build/outputs/apk/release/app-release.apk"
else
    APK_PATH="$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"
fi

echo "=== USB DeckRec Deploy Script ==="
echo "  Variant:        $BUILD_VARIANT"
echo "  APK path:       $APK_PATH"
echo "  Force rebuild:  $FORCE_BUILD"
echo "  Show logcat:    $SHOW_LOGCAT"
echo ""

# ── Step 1: Build if needed ────────────────────────────────────────────
if [ "$FORCE_BUILD" = true ] || [ ! -f "$APK_PATH" ]; then
    echo ">>> [1/3] Building APK with Docker..."
    cd "$PROJECT_DIR"
    if [ "$BUILD_VARIANT" = "release" ]; then
        docker compose -f docker/docker-compose.yml run --rm builder \
            bash -c "cd /app && ./gradlew assembleRelease"
    else
        docker compose -f docker/docker-compose.yml run --rm builder \
            bash -c "cd /app && ./gradlew assembleDebug"
    fi

    if [ ! -f "$APK_PATH" ]; then
        echo "ERROR: APK not found at $APK_PATH"
        exit 1
    fi
    echo "  APK built: $APK_PATH"
else
    echo ">>> [1/3] Skipping build — APK already exists (use --build to force rebuild)"
fi
echo ""

# ── Step 2: Check ADB ──────────────────────────────────────────────────
echo ">>> [2/3] Checking ADB connection..."
if ! command -v adb &>/dev/null; then
    echo "ERROR: adb not found. Install Android Platform Tools."
    exit 1
fi

DEVICES=$(adb devices | grep -v "List" | grep -v "^$" | grep "device$" || true)
if [ -z "$DEVICES" ]; then
    echo "ERROR: No Android device connected. Enable USB debugging."
    exit 1
fi

echo "$DEVICES" | while read -r line; do
    echo "  Device: $(echo "$line" | awk '{print $1}')"
done
echo ""

# ── Step 3: Install ────────────────────────────────────────────────────
echo ">>> [3/3] Installing APK..."
echo "  Uninstalling previous version (Docker debug key changes each build)..."
adb uninstall com.usbdeckrec 2>/dev/null || true
sleep 1
echo ""
echo "  Installing: adb install $(basename "$APK_PATH")"
adb install "$APK_PATH"
ADB_EXIT=$?
echo ""
if [ $ADB_EXIT -eq 0 ]; then
    echo "  ✓ Install successful"
else
    echo "  ⚠ Install failed (exit code $ADB_EXIT)"
fi
echo ""

# ── Optional: Show filtered logcat ─────────────────────────────────────
if [ "$SHOW_LOGCAT" = true ]; then
    echo "--- Tailing filtered logcat (Ctrl+C to stop) ---"
    echo "  Tags: DeckRec_Audio, DeckRec_UsbIso, DeckRec_MIDI, DeckRec_USB,"
    echo "        DeckRec_Service, DeckRec_Playback, DeckRec (generic)"
    echo "  In-app debug logs are visible at the bottom of the app screen"
    echo ""
    adb logcat -s \
        DeckRec_Audio:V \
        DeckRec_UsbIso:V \
        DeckRec_MIDI:V \
        DeckRec_USB:V \
        DeckRec_Service:V \
        DeckRec_Playback:V \
        DeckRec:V \
        --format=time
fi
