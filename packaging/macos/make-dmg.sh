#!/bin/sh
# Builds the LearnSphere disk image from the signed app, and signs the image.
# The image holds the app and a link to the Applications folder, with the app icon on the volume.
# hdiutil copies the signed app as it is: nothing touches its signature after sign-app.sh.
#
# Usage: make-dmg.sh <LearnSphere.app> <out.dmg> <identity> <volume icon.icns>
#   identity  SHA-1 or name of a "Developer ID Application" identity, or "-" to sign ad hoc
set -eu

app=$1
dmg=$2
identity=$3
icon=$4
name=$(basename "$app" .app)

staging=$(mktemp -d "$(dirname "$dmg")/dmg.XXXXXX")
rw="$staging.rw.dmg"
mount=""
cleanup() {
    if [ -n "$mount" ]; then
        hdiutil detach -quiet -force "$mount" 2>/dev/null || true
        rmdir "$mount" 2>/dev/null || true
    fi
    rm -rf "$staging" "$rw"
}
trap cleanup EXIT

# Clone on APFS (no extra space for the models); copy elsewhere. Both keep symbolic links.
cp -c -R "$app" "$staging/" 2>/dev/null || { rm -rf "$staging/$(basename "$app")"; cp -R "$app" "$staging/"; }
ln -s /Applications "$staging/Applications"
cp "$icon" "$staging/.VolumeIcon.icns"

rm -f "$dmg"
# A read-write image first, so the volume can be marked as having a custom icon.
hdiutil create -quiet -volname "$name" -srcfolder "$staging" -fs HFS+ -format UDRW -ov "$rw"
mount=$(mktemp -d)
hdiutil attach -quiet -nobrowse -noautoopen -mountpoint "$mount" "$rw"
SetFile -a C "$mount" 2>/dev/null || xattr -wx com.apple.FinderInfo \
    "0000000000000000040000000000000000000000000000000000000000000000" "$mount" 2>/dev/null || true
hdiutil detach -quiet "$mount"
rmdir "$mount"
mount=""
hdiutil convert -quiet "$rw" -format ULFO -o "$dmg"

if [ "$identity" = "-" ]; then
    codesign --force --timestamp=none --sign "$identity" "$dmg"
else
    codesign --force --timestamp --sign "$identity" "$dmg"
fi
echo "Built $(basename "$dmg") ($(du -h "$dmg" | cut -f 1))"
