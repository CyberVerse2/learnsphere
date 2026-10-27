#!/bin/sh
# Notarizes a signed app or disk image with Apple's notary service, staples the ticket to it and checks it.
# An app is uploaded as a zip archive and the ticket is stapled to the app itself, so it opens offline
# wherever it is copied; packageMac notarizes the app first, then the disk image that holds it.
#
# Usage: notarize.sh <keychain profile> <LearnSphere.app | file.dmg>
# The profile holds the notary credentials, stored once with:
#   xcrun notarytool store-credentials <profile> --apple-id <Apple ID> --team-id <team ID> --password <app-specific password>
set -eu

profile=$1
target=${2%/}

if ! xcrun notarytool history --keychain-profile "$profile" > /dev/null 2>&1; then
    echo "No notary credentials in the keychain profile \"$profile\". Store them once with:" >&2
    echo "  xcrun notarytool store-credentials \"$profile\" --apple-id <Apple ID> --team-id <team ID> --password <app-specific password>" >&2
    exit 1
fi

# Next to the target, so a build that is stopped leaves nothing outside the build folder.
work=$(mktemp -d "$(dirname "$target")/notarize.XXXXXX")
trap 'rm -rf "$work"' EXIT
case "$target" in
    *.app)
        upload="$work/$(basename "$target" .app).zip"
        # ditto keeps the symbolic links and the signatures inside the bundle as they are.
        ditto -c -k --keepParent "$target" "$upload"
        ;;
    *.dmg)
        upload=$target
        ;;
    *)
        echo "Not an app or a disk image: $target" >&2
        exit 2
        ;;
esac

result="$work/result.json"
echo "Uploading $(basename "$target") ($(du -sh "$upload" | cut -f 1)) for notarization"
xcrun notarytool submit "$upload" --keychain-profile "$profile" --wait --output-format json > "$result" || true
id=$(plutil -extract id raw -o - "$result" 2>/dev/null || true)
status=$(plutil -extract status raw -o - "$result" 2>/dev/null || true)
if [ -z "$id" ]; then
    echo "The notary service did not accept the upload:" >&2
    cat "$result" >&2
    exit 1
fi
echo "Notarization $id: $status"
if [ "$status" != "Accepted" ]; then
    echo "Apple's log for $id:" >&2
    xcrun notarytool log "$id" --keychain-profile "$profile" >&2 || true
    exit 1
fi

xcrun stapler staple "$target"
xcrun stapler validate "$target"
