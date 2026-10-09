param(
  [string]$Note = "",
  [switch]$KeepAssets,
  [switch]$PurgeOld
)
# One-shot debug build + push with an incrementing build number.
#
# Why this exists: the Download folder on the phone ends up holding several
# APKs at once (one of them 900MB), and versionName used to stay 1.0.0 forever.
# On the real device we lost a whole test round because the new APK was never
# actually installed and nothing in the logs could prove which build was running.
# So the number goes into three places: the APK file name, versionName/Code,
# and the first log line of every session.
#
# ASCII only on purpose: Windows PowerShell 5.1 decodes BOM-less UTF-8 as GBK,
# and a single Chinese comment can break the whole parser.
$ErrorActionPreference = "Continue"
# "Continue" on purpose. Both gradlew and adb write normal chatter to stderr
# (javac's "uses or overrides a deprecated API" note, adb's progress line), and
# Windows PowerShell promotes a native stderr line to an ErrorRecord - under
# "Stop" that aborts the script even though the build actually succeeded.
# The real checks are done by hand below: exit codes and Test-Path.
$repo = Split-Path -Parent $PSScriptRoot
Set-Location $repo

$numFile = Join-Path $repo ".buildnum"
$n = 0
if (Test-Path $numFile) { $n = [int](Get-Content $numFile -Raw).Trim() }
$clockBuildNo = [long][DateTimeOffset]::UtcNow.ToUnixTimeSeconds() - 1577836800
$n = [Math]::Max($clockBuildNo, $n + 1)

$appkDir = Join-Path $repo "app\build\outputs\apk\debug"
$apk = Join-Path $appkDir ("vtrans-debug-b{0}.apk" -f $n)

Write-Host ("=== build b{0} (versionName 1.0.0-b{0}, versionCode {1}) ===" -f $n, (10000 + $n))

if (-not $KeepAssets) {
  & (Join-Path $repo "scripts\slim_apk.ps1") stash
  # If the stash silently failed we would ship a 900MB package and not notice.
  if (Test-Path (Join-Path $repo "app\src\main\assets\models\nllb")) {
    throw "slim stash did not take effect - aborting before a needless 900MB build"
  }
}
try {
  & (Join-Path $repo "gradlew.bat") :app:assembleDebug --offline "-PbuildNo=$n"
  if ($LASTEXITCODE -ne 0) { throw "gradle assembleDebug failed (exit $LASTEXITCODE)" }
}
finally {
  if (-not $KeepAssets) { & (Join-Path $repo "scripts\slim_apk.ps1") restore }
}

if (-not (Test-Path $apk)) { throw "APK not produced: $apk" }

$named = $apk

$sizeMB = [math]::Round((Get-Item $named).Length / 1MB, 1)
$md5Local = (Get-FileHash $named -Algorithm MD5).Hash.ToLower()

$dst = "/sdcard/Download/vtrans-b$n.apk"
Write-Host ("pushing vtrans-b{0}.apk ({1} MB) ..." -f $n, $sizeMB)
adb push $named $dst | Out-Null
if ($LASTEXITCODE -ne 0) { throw "adb push failed" }

$md5Dev = ((adb shell md5sum $dst) -split '\s+')[0]
if ($md5Dev -ne $md5Local) {
  throw ("MD5 MISMATCH local={0} device={1} -> the phone did NOT get this build" -f $md5Local, $md5Dev)
}
Write-Host ("md5 OK  {0}" -f $md5Local)

if ($PurgeOld) {
  # Remove our previous debug drops, nothing else. These are build artifacts,
  # not user data; the point is that a stale 900MB package must not be tappable.
  adb shell "rm -f /sdcard/Download/vtrans-en-zh-debug.apk /sdcard/Download/vtrans-en-zh.apk /sdcard/Download/vtrans-b*.apk /sdcard/Download/vtrans-debug-b*.apk" 2>$null | Out-Null
  adb push $named $dst 2>$null | Out-Null
  if ($LASTEXITCODE -ne 0) { throw "adb re-push after purge failed" }
  $md5Dev = ((adb shell md5sum $dst 2>$null) -split '\s+')[0]
  if ($md5Dev -ne $md5Local) {
    throw ("MD5 MISMATCH after purge local={0} device={1}" -f $md5Local, $md5Dev)
  }
  Write-Host "old debug apks purged, current one re-pushed and re-verified"
}

# Log capture: fresh file per build, old logcat writers killed, ring buffer cleared.
# Reusing one file mixes yesterday's run into today's and makes the evidence useless.
adb shell "kill `$(pidof logcat)" 2>$null | Out-Null
adb shell "rm -f /data/local/tmp/vrun*.log" 2>$null | Out-Null
adb logcat -c 2>$null
$log = "/data/local/tmp/vrun$n.log"
adb shell "nohup logcat -v threadtime -f $log -r 60000 -n 4 -s VTransPerf:V TranslateService:V StreamingAsr:V AsrEngine:V ModelManager:V MainActivity:V sherpa-onnx:W AndroidRuntime:E DEBUG:V '*':S >/dev/null 2>&1 &" 2>$null | Out-Null

Set-Content -Path $numFile -Value $n -NoNewline
if ([int](Get-Content $numFile -Raw).Trim() -ne $n) { throw "build ledger not written" }
$ledger = Join-Path $repo ".buildlog"
"{0}`t{1}`t{2}MB`t{3}`t{4}" -f $n, (Get-Date -Format "yyyy-MM-dd HH:mm:ss"), $sizeMB, $md5Local, $Note |
  Out-File -FilePath $ledger -Append -Encoding utf8

$installed = (adb shell dumpsys package com.example.vtrans 2>$null | Select-String "versionName|lastUpdateTime") -join "  "

Write-Host ""
Write-Host ("INSTALL THIS FILE : Download/vtrans-b{0}.apk   ({1} MB)" -f $n, $sizeMB)
Write-Host ("LOG FIRST LINE    : build b{0}  (TranslateService logs it as b{0} / 1.0.0-b{0})" -f $n)
Write-Host ("LOG CAPTURE       : {0}" -f $log)
Write-Host ("NOW INSTALLED     : {0}" -f $installed.Trim())
Write-Host ("If versionName is not 1.0.0-b{0} you have not installed this build yet." -f $n)
