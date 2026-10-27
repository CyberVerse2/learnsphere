#!/bin/sh
# Finds the identity that signs the macOS app and writes it (a certificate SHA-1) to <out file>.
#
# Usage: identity.sh <out file> [identity]
#   identity  name, team ID or SHA-1 of a "Developer ID Application" identity in the keychain, or "-"
#             to sign ad hoc (local testing only: an ad hoc app cannot be notarized). Without it, the
#             keychain must hold exactly one valid Developer ID Application identity.
set -eu

out=$1
wanted=${2:-}
mkdir -p "$(dirname "$out")"

if [ "$wanted" = "-" ]; then
    echo "-" > "$out"
    echo "Signing ad hoc: the app runs on this Mac only and cannot be notarized."
    exit 0
fi

# Lines look like:   3) F8F9...5602 "Developer ID Application: Name (TEAMID)"
candidates=$(security find-identity -v -p codesigning |
    sed -n 's/^ *[0-9][0-9]*) \([0-9A-F]\{40\}\) "\(Developer ID Application: .*\)"$/\1 \2/p' | sort -u)
if [ -n "$wanted" ]; then
    matches=$(printf '%s\n' "$candidates" | awk -v w="$wanted" 'NF && index($0, w) > 0')
else
    matches=$candidates
fi
count=$(printf '%s' "$matches" | grep -c . || true)

if [ "$count" -eq 0 ]; then
    echo "No valid \"Developer ID Application\" identity${wanted:+ matching \"$wanted\"} in the keychain." >&2
    echo "Identities available for code signing (security find-identity -v -p codesigning):" >&2
    security find-identity -v -p codesigning >&2 || true
    exit 1
fi
if [ "$count" -gt 1 ]; then
    echo "Several Developer ID Application identities match; choose one with -Plearnsphere.signingIdentity=<SHA-1>:" >&2
    printf '%s\n' "$matches" >&2
    exit 1
fi

sha=${matches%% *}
echo "$sha" > "$out"
echo "Signing with ${matches#* } ($sha)"
