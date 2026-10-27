#!/bin/sh
# Signs LearnSphere.app for distribution, from the inside out: every Mach-O file (the Java runtime's
# libraries, llama-server and its libraries) with the hardened runtime and a secure timestamp, then the
# bundled Java runtime, then the app itself with the entitlements the Java virtual machine needs.
# Native libraries inside jars are signed earlier, when the jars are staged.
#
# Usage: sign-app.sh <identity> <entitlements.plist> <LearnSphere.app>
#   identity  SHA-1 or name of a "Developer ID Application" identity, or "-" to sign ad hoc
set -eu

identity=$1
entitlements=$2
app=$3

if [ ! -f "$app/Contents/Info.plist" ]; then
    echo "Not an app bundle: $app" >&2
    exit 1
fi
if [ "$identity" = "-" ]; then
    timestamp=--timestamp=none
else
    timestamp=--timestamp
fi

# Extended attributes such as Finder info would make codesign refuse the bundle. Some (provenance)
# cannot be removed and do not matter, hence no failure here.
xattr -cr "$app" 2>/dev/null || true

list=$(mktemp)
trap 'rm -f "$list"' EXIT

runtime="$app/Contents/runtime"
main_executable() {
    echo "$1/Contents/MacOS/$(/usr/libexec/PlistBuddy -c 'Print :CFBundleExecutable' "$1/Contents/Info.plist")"
}
# Mach-O files only; symbolic links are sealed by the bundle signature, not signed themselves. The main
# executables of the app and of the Java runtime are left to their bundle's signature: codesign treats
# them as the bundle itself.
find "$app" -type f ! -path '*/_CodeSignature/*' -exec file -F '|' {} + |
    awk -F '|' -v skip1="$(main_executable "$app")" -v skip2="$(main_executable "$runtime")" \
        '$2 ~ /Mach-O/ && $1 != skip1 && $1 != skip2 { print $1 }' > "$list"
echo "Signing $(wc -l < "$list" | tr -d ' ') executables and libraries in $(basename "$app")"
tr '\n' '\0' < "$list" |
    xargs -0 -n 16 -P 4 codesign --force --options runtime "$timestamp" --sign "$identity"

# Nested bundles before the bundle that holds them.
codesign --force --options runtime "$timestamp" --sign "$identity" "$runtime"
codesign --force --options runtime "$timestamp" --entitlements "$entitlements" --sign "$identity" "$app"

codesign --verify --deep --strict --verbose=2 "$app"
