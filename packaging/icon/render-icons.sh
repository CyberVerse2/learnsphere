#!/bin/sh
# Renders the app icon and writes the committed icon files:
#   packaging/macos/LearnSphere.icns     (iconutil, 16-1024 px)
#   packaging/windows/LearnSphere.ico    (16-256 px)
#   packaging/icon/LearnSphere-1024.png  (preview)
# Needs macOS (iconutil) and Java 21 or later (JAVA_HOME, or the java on PATH).
set -eu

here=$(cd "$(dirname "$0")" && pwd)
packaging=$(dirname "$here")
java="${JAVA_HOME:+$JAVA_HOME/bin/}java"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

"$java" -Djava.awt.headless=true "$here/IconRenderer.java" "$work"
iconutil --convert icns --output "$packaging/macos/LearnSphere.icns" "$work/LearnSphere.iconset"
cp "$work/LearnSphere.ico" "$packaging/windows/LearnSphere.ico"
cp "$work/LearnSphere-1024.png" "$here/LearnSphere-1024.png"
echo "Wrote LearnSphere.icns, LearnSphere.ico and LearnSphere-1024.png"
