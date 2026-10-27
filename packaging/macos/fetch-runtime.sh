#!/bin/sh
# Downloads the llama.cpp build for macOS (Apple silicon) and the models pinned in packaging/runtime.lock
# into a runtime folder, checking each file's SHA-256. Files already present with the right checksum are
# kept, so running it again only fetches what is missing.
#
# Usage: packaging/macos/fetch-runtime.sh [runtime folder] [--no-models]
#   The folder defaults to runtime/ at the repository root.
set -eu

here=$(cd "$(dirname "$0")" && pwd)
lock="$here/../runtime.lock"
dest="$(cd "$here/../.." && pwd)/runtime"
models=true
for arg in "$@"; do
    case "$arg" in
        --no-models) models=false ;;
        -*) echo "Usage: $0 [runtime folder] [--no-models]" >&2; exit 2 ;;
        *) dest=$arg ;;
    esac
done
mkdir -p "$dest"

sha256() {
    shasum -a 256 "$1" | cut -d ' ' -f 1
}

grep -v '^[[:space:]]*#' "$lock" | while read -r platform sha path url; do
    [ -n "${platform:-}" ] || continue
    case "$platform" in macos-arm64 | all) ;; *) continue ;; esac
    case "$path" in models/*) [ "$models" = true ] || continue ;; esac

    target="$dest/$path"
    if [ -f "$target" ] && [ "$(sha256 "$target")" = "$sha" ]; then
        echo "Up to date: $path"
    else
        mkdir -p "$(dirname "$target")"
        echo "Downloading $path"
        curl --fail --location --retry 3 --progress-bar --output "$target.part" "$url"
        actual=$(sha256 "$target.part")
        if [ "$actual" != "$sha" ]; then
            rm -f "$target.part"
            echo "Checksum mismatch for $path: expected $sha, got $actual" >&2
            exit 1
        fi
        mv "$target.part" "$target"
    fi

    case "$path" in
        *.tar.gz)
            # The archive holds one folder, llama-<build>/.
            build=$(echo "$path" | sed -n 's/.*llama-\(b[0-9]*\)-bin.*/\1/p')
            if [ ! -x "$dest/llama/macos-arm64/llama-$build/llama-server" ]; then
                mkdir -p "$dest/llama/macos-arm64"
                tar -xzf "$target" -C "$dest/llama/macos-arm64"
                echo "Unpacked llama.cpp $build into llama/macos-arm64/llama-$build"
            fi
            ;;
    esac
done
