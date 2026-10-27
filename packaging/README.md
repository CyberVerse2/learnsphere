# Packaging LearnSphere

LearnSphere ships as a signed and notarized disk image (`.dmg`) for Apple silicon Macs and as an `.msi`
installer for 64-bit Windows. Each one carries its own Java 25 runtime, the llama.cpp build b11550
(`llama-server` and the libraries it loads) and the models, so the installed app works offline from its
first launch.

```sh
# macOS: a signed disk image without the models, for a quick check
./gradlew :app:packageMac -PbundleModels=false

# macOS: the release disk image, notarized and stapled
./gradlew :app:packageMac -Plearnsphere.notarize=true -Plearnsphere.packageVersion=1.0.0

# Windows (on Windows, with the WiX Toolset installed)
./gradlew :app:packageWindows -Plearnsphere.packageVersion=1.0.0
```

Installers are written to `app/build/package/`. Intermediate files go to `app/build/package-work/`.
The tasks live in `app/packaging.gradle.kts`.

## What is in this folder

| Path | Purpose |
|---|---|
| `runtime.lock` | The llama.cpp builds and model files that installers bundle, with their download URLs and SHA-256 checksums |
| `macos/fetch-runtime.sh`, `windows/fetch-runtime.ps1` | Download and check everything in `runtime.lock` into the runtime folder |
| `macos/entitlements.plist` | Entitlements of the Java launcher in the signed app |
| `macos/identity.sh` | Finds the Developer ID Application identity in the keychain |
| `macos/info-plist.sh` | Sets the app's minimum macOS version and removes permission prompts the app never needs |
| `macos/sign-app.sh` | Signs every executable and library in the app, then the app |
| `macos/make-dmg.sh` | Builds and signs the disk image |
| `macos/notarize.sh` | Notarizes the disk image and staples the ticket |
| `macos/verify.sh` | Checks signatures, entitlements, the stapled ticket and Gatekeeper's verdict |
| `windows/main.wxs` | The WiX source for the `.msi`, which keeps large payloads in cabinet files next to it |
| `macos/LearnSphere.icns`, `windows/LearnSphere.ico` | App icons |
| `icon/` | The icon renderer and a 1024 px preview |
| `legal/` | Third-party notices and licence texts that ship with the app |

## Prerequisites

Both platforms build with the Gradle wrapper, which downloads the Java 25 toolchain when it is missing.

**macOS**: an Apple silicon Mac with the Xcode command-line tools (`codesign`, `notarytool`, `stapler`,
`hdiutil`), a "Developer ID Application" certificate with its private key in the login keychain, and
about 10 GB of free disk space for a build with the models.

**Windows**: Windows 10 or 11 (x64) and the WiX Toolset 4 or later on `PATH` with its Util and UI
extensions. With the .NET SDK installed:

```powershell
dotnet tool install --global wix --version 5.0.2
wix extension add --global WixToolset.Util.wixext/5.0.2 WixToolset.UI.wixext/5.0.2
```

A Windows build with the models needs about 20 GB of free disk space.

## The runtime folder

Installers copy the runtime folder (layout in `docs/ARCHITECTURE.md`, "Runtime files") into the app as
`<app dir>/runtime`, where the app finds it. The folder is `runtime/` at the repository root unless
`-Plearnsphere.runtimeDir=<folder>` names another one. Fill it with:

```sh
packaging/macos/fetch-runtime.sh                 # llama.cpp for macOS and the models
packaging/macos/fetch-runtime.sh --no-models     # llama.cpp only
```

```powershell
packaging\windows\fetch-runtime.ps1              # llama.cpp for Windows (Vulkan) and the models
packaging\windows\fetch-runtime.ps1 -NoModels
```

Both scripts check every download against `runtime.lock` and skip files that are already present and
correct. To change the llama.cpp build or a model, edit its row in `runtime.lock`: Hugging Face URLs name a
commit, so a URL always serves the same file. Compute the checksum with `shasum -a 256 <file>` (macOS) or
`Get-FileHash <file>` (Windows).

The installers include only this platform's `llama-server`, the libraries it loads and the llama.cpp
licence files; the other llama.cpp tools stay out.

## Build properties

| Property | Default | Effect |
|---|---|---|
| `-Plearnsphere.runtimeDir=<folder>` | `runtime/` | The runtime folder to bundle |
| `-PbundleModels=false` | models included | Leaves the models out: a small installer for checking the packaging itself |
| `-Plearnsphere.packageVersion=<x.y.z>` | `1.0.0` | Installer version. jpackage on macOS refuses versions starting with 0; Windows upgrades need a higher version than the installed one |
| `-Plearnsphere.signingIdentity=<id>` | found in the keychain | Name, team ID or SHA-1 of the Developer ID Application identity; `-` signs ad hoc |
| `-Plearnsphere.notarize=true` | `false` | Notarizes the disk image and staples the ticket |
| `-Plearnsphere.notaryProfile=<name>` | `learnsphere-notary` | The `notarytool` keychain profile |

The packaging tasks always start from scratch rather than reusing earlier output.

## Signing on macOS

`packageMac` signs with the one valid "Developer ID Application" identity that
`security find-identity -v -p codesigning` lists. With several, name one:

```sh
./gradlew :app:packageMac -Plearnsphere.signingIdentity="Developer ID Application: Jane Doe (TEAMID1234)"
```

`-Plearnsphere.signingIdentity=-` signs ad hoc instead; such an app runs on the Mac that built it and
cannot be notarized.

The build signs from the inside out, every signature with the hardened runtime and a secure timestamp:

1. Native libraries inside the jars (JavaFX, SQLite) are signed in place, after the libraries for other
   platforms are removed. The libraries keep their signature when the app unpacks them at run time.
2. Every executable and library in the app: the Java runtime, `llama-server` and its libraries.
3. The bundled Java runtime, then the app with `macos/entitlements.plist`.
4. The disk image.

The entitlements apply to the Java launcher only and are the three the Java virtual machine needs:
`allow-jit`, `allow-unsigned-executable-memory` and `disable-library-validation`. `llama-server` runs with
no entitlements.

Before signing, `macos/info-plist.sh` sets `LSMinimumSystemVersion` to the newest macOS version that any
executable or library in the app requires (13.3, set by llama.cpp's Metal backend), so older systems
decline to open the app.

## Notarizing on macOS

Store the notary credentials in the keychain once, under the profile name `learnsphere-notary`. With an
app-specific password (created at account.apple.com, Sign-In and Security):

```sh
xcrun notarytool store-credentials learnsphere-notary \
  --apple-id <Apple ID> --team-id <team ID> --password <app-specific password>
```

or with an App Store Connect API key:

```sh
xcrun notarytool store-credentials learnsphere-notary \
  --key <AuthKey_XXXXXXXXXX.p8> --key-id <key ID> --issuer <issuer ID>
```

Then build with `-Plearnsphere.notarize=true`. The task uploads the disk image, waits for Apple's verdict,
prints Apple's log when the submission is rejected, staples the ticket to the disk image and validates it.
A disk image with the models is about 4.3 GB, so the upload and Apple's processing take a while.

Without `-Plearnsphere.notarize=true` the build stops after signing and says so.

## Verifying

`packageMac` ends with `macos/verify.sh`, which checks:

- the disk image's signature;
- `codesign --verify --deep --strict` on the app;
- the hardened runtime, a secure timestamp and the Developer ID authority on every executable and library;
- exactly the three expected entitlements;
- for a notarized build, the stapled ticket and Gatekeeper's verdict on the disk image
  (`spctl -a -vv -t install`) and on the app.

To check a disk image again, for example after downloading it from the release:

```sh
packaging/macos/verify.sh app/build/package/LearnSphere-1.0.0.dmg true
spctl -a -vv -t install app/build/package/LearnSphere-1.0.0.dmg   # accepted, source=Notarized Developer ID
```

## The Windows installer

`packageWindows` builds a per-user installer: it needs no administrator rights, installs into
`%LOCALAPPDATA%\LearnSphere` (the user can choose another folder) and adds LearnSphere to the Start menu.
The installer's upgrade code is fixed in `app/packaging.gradle.kts`; installing a higher version replaces
the installed one. Changing the upgrade code makes Windows treat the result as a different product.

Windows Installer limits the size of each file to 2 GB and of the `.msi` itself to 2 GB, so:

- Model files larger than 2,000,000,000 bytes (the chat model) are split with `llama-gguf-split` into
  parts named `<name>-00001-of-0000N.gguf`. `llama-server` loads a split model when given its first part.
- With the models bundled, the installer keeps its payload in `LearnSphere1.cab`, `LearnSphere2.cab` and
  so on, next to `LearnSphere-<version>.msi`. The `.msi` and all its `.cab` files must be in the same
  folder when it runs, so they are published together, for example as one `.zip`.
- Without the models (`-PbundleModels=false`) the installer is a single `.msi`.

The installer is not Authenticode-signed, so Windows SmartScreen asks the user to confirm before it runs.

## Releasing

1. Pick the version, for example `1.2.0`, and tag the commit:

   ```sh
   git tag v1.2.0
   git push origin v1.2.0
   ```

2. **Windows**: the tag starts the Build workflow, which builds the installer with version `1.2.0` and
   uploads it as the `LearnSphere-windows-x64` artifact (the `.msi` and its `.cab` files). Download it from
   the workflow run:

   ```sh
   gh run download --name LearnSphere-windows-x64 --dir dist/windows
   ```

3. **macOS**: on an Apple silicon Mac with the full runtime folder, at the tagged commit:

   ```sh
   ./gradlew :app:packageMac -Plearnsphere.notarize=true -Plearnsphere.packageVersion=1.2.0
   ```

   The build ends with the verification above; `spctl` reports `source=Notarized Developer ID`.

4. Publish the disk image, and the Windows `.msi` with its `.cab` files as one `.zip`. GitHub release
   assets are limited to 2 GB each, which the Windows files fit and the disk image with the models does
   not, so the disk image goes to storage that accepts larger files.

## Continuous integration

`.github/workflows/build.yml` runs `./gradlew build` on `macos-latest` and `windows-latest` for every push
and pull request. For pushes to `main`, tags and manual runs it then builds the Windows installer:

1. Installs WiX 5.0.2 with its Util and UI extensions.
2. Restores `runtime/` from the Actions cache, keyed on `runtime.lock` and `windows/fetch-runtime.ps1`;
   when the cache misses, `windows/fetch-runtime.ps1` downloads llama.cpp b11550 for Windows (Vulkan, x64)
   and the models from Hugging Face.
3. Runs `:app:packageWindows`, with the version taken from the tag when the run is for a tag.
4. Uploads the `.msi` and `.cab` files as the `LearnSphere-windows-x64` artifact, kept for 14 days.

## Icons

`icon/IconRenderer.java` draws the icon, a lapis sphere with an orbit on warm paper, in the geometry the
app's own sphere artwork uses. `icon/render-icons.sh` (macOS) renders it and writes the committed files
`macos/LearnSphere.icns`, `windows/LearnSphere.ico` and `icon/LearnSphere-1024.png`:

```sh
packaging/icon/render-icons.sh
```

## Third-party notices

Installers include a `legal/` folder next to the application jars, assembled by the `collectNotices` task:

- everything in `packaging/legal/`, starting with `THIRD-PARTY-NOTICES.txt`, which lists each component and
  its licence;
- the fonts' SIL Open Font License texts, in `fonts/`;
- the licence and notice files from each Java library's jar, in `libraries/<jar>/`; this includes the
  JPEG 2000 decoder's BSD and JJ2000 licences.

The bundled Java runtime keeps its own notices in its `legal` folder. When a dependency is added, add its
row to `THIRD-PARTY-NOTICES.txt`, and its licence text under `legal/licenses/` when its jar carries none.

## Disk space

Builds with the models use hard links and APFS clones where they can, but a Windows build and the finished
disk image still take several gigabytes. Remove the output once the installers are published:

```sh
rm -rf app/build/package app/build/package-work
```
