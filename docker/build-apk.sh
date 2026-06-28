#!/bin/bash
# USB DeckRec — One-command APK Builder
# Usage: ./docker/build-apk.sh
#
# Multi-stage build with Docker Compose:
#   1. Builds the base image (SDK + NDK + Gradle) — cached layer
#   2. Compiles the APK with Gradle dependency caching (Docker volume)
#   3. Runs unit tests
#   4. Extracts the APK to the project root
#
# ⚡ Fast rebuilds: the base image is cached, and Gradle deps are in a volume.
#    Only source code recompilation happens on subsequent runs.
#
# Requirements: docker, docker compose (v2)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
APK_DEST="${PROJECT_DIR}/USBDeckRec-debug.apk"
COMPOSE="docker compose -f ${SCRIPT_DIR}/docker-compose.yml"

echo "============================================"
echo "  USB DeckRec — APK Builder"
echo "  Multi-stage | Compose | Cached"
echo "============================================"
echo ""

# ── Step 1: Build the base image (SDK + NDK + Gradle) ──────────────────
echo "[1/3] Building base image (SDK / NDK / Gradle)"
echo "      (cached — only rebuilds if Dockerfile changes)"
echo ""
${COMPOSE} build base
echo ""

# ── Step 2: Run tests ──────────────────────────────────────────────────
echo "[2/3] Running unit tests..."
echo ""
${COMPOSE} run --rm test
echo ""

# ── Step 3: Compile the APK ────────────────────────────────────────────
echo "[3/3] Compiling APK..."
echo ""
${COMPOSE} run --rm build
echo ""

# ── Extract APK from builder image ─────────────────────────────────────
echo ""
echo "Extracting APK..."
APK_PATH="${PROJECT_DIR}/app/build/outputs/apk/debug/app-debug.apk"
if [ -f "${APK_PATH}" ]; then
    cp "${APK_PATH}" "${APK_DEST}"
    echo "✅ APK ready: ${APK_DEST}"
    echo "   Size: $(du -h "${APK_DEST}" | cut -f1)"
else
    echo "⚠️  APK not found — check for build errors above."
    echo "   Expected at: ${APK_PATH}"
fi

echo ""
echo "============================================"
echo "  Done!"
echo "  APK: ${APK_DEST}"
echo "============================================"
