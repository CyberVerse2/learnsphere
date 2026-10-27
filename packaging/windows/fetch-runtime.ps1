<#
.SYNOPSIS
Downloads the llama.cpp build for Windows x64 (Vulkan) and the models pinned in packaging/runtime.lock.

.DESCRIPTION
Fills a runtime folder with the layout described in docs/ARCHITECTURE.md ("Runtime files"), checking each
file's SHA-256. Files already present with the right checksum are kept, so running it again only fetches
what is missing.

.PARAMETER Destination
The runtime folder. Defaults to runtime\ at the repository root.

.PARAMETER NoModels
Fetch only the llama.cpp build.
#>
[CmdletBinding()]
param(
    [string] $Destination = (Join-Path $PSScriptRoot '..\..\runtime'),
    [switch] $NoModels
)

$ErrorActionPreference = 'Stop'
$lock = Join-Path $PSScriptRoot '..\runtime.lock'
New-Item -ItemType Directory -Force -Path $Destination | Out-Null
$Destination = (Resolve-Path $Destination).Path

foreach ($line in Get-Content $lock) {
    if ($line -match '^\s*(#|$)') { continue }
    $platform, $sha, $path, $url = -split $line
    if ($platform -notin @('windows-x64', 'all')) { continue }
    if ($NoModels -and $path.StartsWith('models/')) { continue }

    $target = Join-Path $Destination $path
    if ((Test-Path $target) -and ((Get-FileHash -Algorithm SHA256 $target).Hash -eq $sha)) {
        Write-Host "Up to date: $path"
    } else {
        New-Item -ItemType Directory -Force -Path (Split-Path $target) | Out-Null
        Write-Host "Downloading $path"
        & curl.exe --fail --location --retry 3 --silent --show-error --output "$target.part" $url
        if ($LASTEXITCODE -ne 0) { throw "Download failed: $url" }
        $actual = (Get-FileHash -Algorithm SHA256 "$target.part").Hash
        if ($actual -ne $sha) {
            Remove-Item "$target.part"
            throw "Checksum mismatch for ${path}: expected $sha, got $actual"
        }
        Move-Item -Force "$target.part" $target
    }

    if ($path -like '*.zip') {
        # The archive holds the files directly; unpack them into llama\windows-x64\llama-<build>\.
        $build = [regex]::Match($path, 'llama-(b\d+)-bin').Groups[1].Value
        $folder = Join-Path $Destination "llama\windows-x64\llama-$build"
        if (-not (Test-Path (Join-Path $folder 'llama-server.exe'))) {
            Expand-Archive -Path $target -DestinationPath $folder -Force
            Write-Host "Unpacked llama.cpp $build into llama\windows-x64\llama-$build"
        }
    }
}
