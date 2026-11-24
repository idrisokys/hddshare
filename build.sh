#!/bin/bash
set -e
cd "$(dirname "$0")"
APP=Kopya.app
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS"
# Apple Silicon (arm64) + macOS 15 ve üstü, tam optimizasyon
swiftc -O -wmo -swift-version 5 -parse-as-library Kopya.swift \
  -target arm64-apple-macos15.0 -o "$APP/Contents/MacOS/Kopya"
cp Info.plist "$APP/Contents/"
mkdir -p "$APP/Contents/Resources"
cp Kopya.icns "$APP/Contents/Resources/Kopya.icns"
codesign --force --sign - "$APP"
echo "Hazır: $(pwd)/$APP"
# Yerelde Finder'da göster (CI'da atla)
if [ -z "$CI" ]; then open -R "$APP"; fi
