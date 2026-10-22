# Builds HealthBridge and installs it on a connected phone (USB or wireless debugging).
# Run:  powershell -ExecutionPolicy Bypass -File .\build-and-install.ps1
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

$jdk = Get-ChildItem "$env:USERPROFILE\.jdks" -Directory -Filter 'jdk-21*' -ErrorAction SilentlyContinue | Select-Object -First 1
if ($jdk) { $env:JAVA_HOME = $jdk.FullName }
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'

& .\gradlew.bat :app:assembleDebug
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$apk = Join-Path $PSScriptRoot 'app\build\outputs\apk\debug\app-debug.apk'
$devices = & $adb devices | Select-String "`tdevice$"
if (-not $devices) {
    Write-Host ""
    Write-Host "No phone found via adb. The APK is here:"
    Write-Host "  $apk"
    exit 0
}

& $adb install -r $apk
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& $adb shell monkey -p se.healthbridge.app -c android.intent.category.LAUNCHER 1 | Out-Null
Write-Host "HealthBridge installed and started."
