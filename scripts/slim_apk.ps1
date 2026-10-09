# Builds a debug APK without the big model assets.
#
# Why: nllb (600MB) + sense-voice (226MB) are already unpacked into external
# storage on the test phone. Pushing that 940MB over USB every iteration costs
# ~32s transfer plus install time, for nothing.
#
# Deliberately conservative: it only moves the two directories into
# app/build/slim_stash (build/ is gitignored) and moves them back afterwards.
# build.gradle is untouched, so release packaging is unaffected.
#
# Usage (run restore even if the build fails):
#   powershell scripts/slim_apk.ps1 stash
#   .\gradlew :app:assembleDebug --offline
#   powershell scripts/slim_apk.ps1 restore
#
# NOTE: keep this file pure ASCII. Windows PowerShell 5.1 decodes BOM-less
# files as GBK, which breaks the parser on any non-ASCII comment.
param([Parameter(Mandatory = $true)][string]$Action)

$ErrorActionPreference = 'Stop'
$root   = Split-Path -Parent $PSScriptRoot
$models = Join-Path $root 'app\src\main\assets\models'
$stash  = Join-Path $root 'app\build\slim_stash'
# vad and zipformer-en stay in the APK: zipformer is the model under test this
# round, and its fresh-unpack path should stay exercised.
$targets = @('nllb', 'sense-voice')

switch ($Action) {
    'stash' {
        New-Item -ItemType Directory -Force -Path $stash | Out-Null
        foreach ($d in $targets) {
            $src = Join-Path $models $d
            $dst = Join-Path $stash $d
            if (Test-Path $src) {
                if (Test-Path $dst) { throw "$dst already exists - restore first" }
                Move-Item $src $dst
                Write-Host "stashed $d"
            } else {
                Write-Host "skipped $d (not in place)"
            }
        }
    }
    'restore' {
        foreach ($d in $targets) {
            $tmp = Join-Path $stash $d
            $dst = Join-Path $models $d
            if (Test-Path $tmp) {
                if (Test-Path $dst) { throw "$dst already exists - refusing to overwrite" }
                Move-Item $tmp $dst
                Write-Host "restored $d"
            }
        }
        if ((Get-ChildItem $stash -Force -ErrorAction SilentlyContinue | Measure-Object).Count -eq 0) {
            Remove-Item $stash -Force -ErrorAction SilentlyContinue
        }
    }
    'status' {
        foreach ($d in $targets) {
            $inModels = Test-Path (Join-Path $models $d)
            $inStash  = Test-Path (Join-Path $stash $d)
            Write-Host ("{0}: assets={1} stash={2}" -f $d, $inModels, $inStash)
        }
    }
    default { throw "Action must be stash / restore / status" }
}
