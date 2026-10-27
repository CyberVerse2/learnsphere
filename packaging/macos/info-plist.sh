#!/bin/sh
# Adjusts the Info.plist that jpackage writes for LearnSphere.app; runs before signing.
#  - LSMinimumSystemVersion becomes the newest macOS version that any executable or library in the app
#    requires (llama.cpp needs 13.3), so older systems decline to open the app instead of it failing later.
#  - The microphone usage description jpackage adds is removed: LearnSphere never uses the microphone.
#
# Usage: info-plist.sh <LearnSphere.app>
set -eu

app=$1
plist="$app/Contents/Info.plist"

minimum=$(find "$app" -type f -exec file -F '|' {} + |
    awk -F '|' '$2 ~ /Mach-O/ { print $1 }' |
    while IFS= read -r file; do
        otool -l "$file" | awk '/LC_BUILD_VERSION/ { found = 1 } found && $1 == "minos" { print $2; found = 0 }'
    done |
    sort -t . -k 1,1n -k 2,2n -k 3,3n | tail -n 1)
if [ -z "$minimum" ]; then
    echo "No minimum macOS version found in $app" >&2
    exit 1
fi

plutil -replace LSMinimumSystemVersion -string "$minimum" "$plist"
plutil -remove NSMicrophoneUsageDescription "$plist" 2>/dev/null || true
echo "$(basename "$app") requires macOS $minimum or later"
