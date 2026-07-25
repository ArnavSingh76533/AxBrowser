#!/usr/bin/env bash
# Manual fallback if Gradle download fails.
# Usage: bash scripts/download-ytdlp.sh
set -e

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ARM64="$ROOT/app/src/main/jniLibs/arm64-v8a"
X86="$ROOT/app/src/main/jniLibs/x86_64"

mkdir -p "$ARM64" "$X86"

echo ""
echo "Downloading yt-dlp_android (ARM64, Bionic libc)..."
curl -L --retry 5 --retry-delay 3 --connect-timeout 30 --max-time 600 \
  "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_android" \
  -o "$ARM64/libytdlp.so" || {
    echo "yt-dlp_android failed, trying yt-dlp_linux_aarch64..."
    curl -L --retry 5 --retry-delay 3 --connect-timeout 30 --max-time 600 \
      "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_linux_aarch64" \
      -o "$ARM64/libytdlp.so"
  }

SIZE=$(wc -c < "$ARM64/libytdlp.so" 2>/dev/null || echo 0)
if [ "$SIZE" -lt 5000000 ]; then
  echo "ARM64 download failed (file too small: $SIZE bytes)"
  rm -f "$ARM64/libytdlp.so"
  exit 1
fi
echo "ARM64: $(du -sh "$ARM64/libytdlp.so" | cut -f1)"

echo ""
echo "Downloading yt-dlp_linux (x86_64, for emulators)..."
curl -L --retry 3 --retry-delay 2 --connect-timeout 30 --max-time 600 \
  "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_linux" \
  -o "$X86/libytdlp.so" || echo "x86_64 download failed (optional)"

echo ""
echo "--- Downloaded files ---"
ls -lh "$ARM64/libytdlp.so" 2>/dev/null || echo "  ARM64: MISSING"
ls -lh "$X86/libytdlp.so" 2>/dev/null   || echo "  x86_64: MISSING (optional)"
echo "------------------------"
echo ""
echo "Now run: ./gradlew assembleDebug"
echo "Then verify with: unzip -l app/build/outputs/apk/debug/app-debug.apk | grep libytdlp"
