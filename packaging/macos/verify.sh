#!/bin/sh
# Checks a built LearnSphere disk image: its signature; the app inside (deep, strict signature check,
# hardened runtime and secure timestamp on every executable and library, the expected entitlements);
# and, once notarized, the stapled ticket and Gatekeeper's verdict on the image and the app.
#
# Usage: verify.sh <file.dmg> <notarized: true|false>
set -eu

dmg=$1
notarized=$2

echo "Disk image signature"
codesign --verify --strict --verbose=2 "$dmg"

mount=$(mktemp -d)
hdiutil attach -quiet -readonly -nobrowse -noautoopen -mountpoint "$mount" "$dmg"
trap 'hdiutil detach -quiet "$mount" 2>/dev/null || hdiutil detach -force -quiet "$mount"; rmdir "$mount"' EXIT
app="$mount/LearnSphere.app"

echo "App signature"
codesign --verify --deep --strict --verbose=2 "$app"

adhoc=false
codesign -dv "$app" 2>&1 | grep -q '^Signature=adhoc' && adhoc=true

problems=0
list=$(mktemp)
find "$app" -type f -exec file -F '|' {} + | awk -F '|' '$2 ~ /Mach-O/ { print $1 }' > "$list"
while IFS= read -r file; do
    details=$(codesign -dv --verbose=4 "$file" 2>&1) || { echo "Not signed: $file"; problems=$((problems + 1)); continue; }
    echo "$details" | grep -q 'flags=.*runtime' || { echo "No hardened runtime: $file"; problems=$((problems + 1)); }
    if [ "$adhoc" = false ]; then
        echo "$details" | grep -q '^Timestamp=' || { echo "No secure timestamp: $file"; problems=$((problems + 1)); }
        echo "$details" | grep -q '^Authority=Developer ID Application' || { echo "Not signed with Developer ID: $file"; problems=$((problems + 1)); }
    fi
done < "$list"
echo "Checked $(wc -l < "$list" | tr -d ' ') executables and libraries"
rm -f "$list"

entitlements=$(codesign -d --entitlements - --xml "$app" 2>/dev/null)
for key in com.apple.security.cs.allow-jit com.apple.security.cs.allow-unsigned-executable-memory \
    com.apple.security.cs.disable-library-validation; do
    echo "$entitlements" | grep -q "<key>$key</key>" || { echo "Missing entitlement $key"; problems=$((problems + 1)); }
done
granted=$(echo "$entitlements" | grep -o '<key>[^<]*</key>' | wc -l | tr -d ' ')
[ "$granted" -eq 3 ] || { echo "Expected 3 entitlements, found $granted"; problems=$((problems + 1)); }

if [ "$problems" -gt 0 ]; then
    echo "$problems problem(s) found" >&2
    exit 1
fi

if [ "$notarized" = true ]; then
    echo "Notarization"
    xcrun stapler validate "$dmg"
    spctl -a -vv -t install "$dmg"
    spctl -a -vv -t exec "$app"
else
    echo "Gatekeeper (expected to reject until the image is notarized):"
    spctl -a -vv -t install "$dmg" 2>&1 || true
fi
echo "Verified $(basename "$dmg")"
