#!/bin/sh
# Notarizes a signed disk image with Apple's notary service, staples the ticket to it and checks it.
#
# Usage: notarize.sh <keychain profile> <file.dmg>
# The profile holds the notary credentials, stored once with:
#   xcrun notarytool store-credentials <profile> --apple-id <Apple ID> --team-id <team ID> --password <app-specific password>
set -eu

profile=$1
dmg=$2

if ! xcrun notarytool history --keychain-profile "$profile" > /dev/null 2>&1; then
    echo "No notary credentials in the keychain profile \"$profile\". Store them once with:" >&2
    echo "  xcrun notarytool store-credentials \"$profile\" --apple-id <Apple ID> --team-id <team ID> --password <app-specific password>" >&2
    exit 1
fi

result=$(mktemp)
trap 'rm -f "$result"' EXIT
echo "Uploading $(basename "$dmg") ($(du -h "$dmg" | cut -f 1)) for notarization"
xcrun notarytool submit "$dmg" --keychain-profile "$profile" --wait --output-format json > "$result"
id=$(plutil -extract id raw -o - "$result")
status=$(plutil -extract status raw -o - "$result")
echo "Notarization $id: $status"
if [ "$status" != "Accepted" ]; then
    echo "Apple's log for $id:" >&2
    xcrun notarytool log "$id" --keychain-profile "$profile" >&2 || true
    exit 1
fi

xcrun stapler staple "$dmg"
xcrun stapler validate "$dmg"
