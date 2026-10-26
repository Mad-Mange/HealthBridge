# Builds HealthBridge and installs it on a connected phone (USB or wireless debugging).
# Run:  powershell -ExecutionPolicy Bypass -File .\build-and-install.ps1
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

$jdk = Get-ChildItem "$env:USERPROFILE\.jdks" -Directory -Filter 'jdk-21*' -ErrorAction SilentlyContinue | Select-Object -First 1
if ($jdk) { $env:JAVA_HOME = $jdk.FullName }
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'

# With a release key in local.properties, build the signed release, so it installs over a version from
# GitHub Releases and the other way round. Otherwise a debug build.
$signed = Select-String -Path local.properties -Pattern '^release\.storeFile=.+' -Quiet -ErrorAction SilentlyContinue
$variant = if ($signed) { 'Release' } else { 'Debug' }

& .\gradlew.bat ":app:assemble$variant"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$apk = Join-Path $PSScriptRoot "app\build\outputs\apk\$($variant.ToLower())\app-$($variant.ToLower()).apk"
$devices = & $adb devices | Select-String "`tdevice$"
if (-not $devices) {
    Write-Host ""
    Write-Host "No phone found via adb. The APK is here:"
    Write-Host "  $apk"
    exit 0
}

& $adb install -r $apk
if ($LASTEXITCODE -ne 0) {
    Write-Host "If it says INSTALL_FAILED_UPDATE_INCOMPATIBLE, the installed HealthBridge is signed with another key."
    Write-Host "Uninstall it first (you'll have to log in to Withings again)."
    exit $LASTEXITCODE
}
& $adb shell monkey -p se.healthbridge.app -c android.intent.category.LAUNCHER 1 | Out-Null
Write-Host "HealthBridge installed and started."
