# Builds a signed release APK and publishes it as a GitHub release, tagged v<versionName>.
# Run:  powershell -ExecutionPolicy Bypass -File .\release.ps1 [-NotesFile notes.md]
# Needs the release.* signing lines in local.properties (see app/build.gradle.kts) and a logged-in gh.
param([string]$NotesFile)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

$props = @(Get-Content local.properties -ErrorAction SilentlyContinue)
if (-not ($props -match '^release\.storeFile=.+')) {
    throw "No release signing key in local.properties (release.storeFile etc., see app/build.gradle.kts)."
}
# The APK is public: Withings defaults from local.properties would be baked into it.
if ($props -match '^withings\.client(Id|Secret)=.+') {
    throw "Comment out withings.clientId/clientSecret in local.properties before a public release."
}

$version = (Select-String -Path app\build.gradle.kts -Pattern 'versionName = "(.+)"').Matches[0].Groups[1].Value
$tag = "v$version"
if (git status --porcelain) { throw "Commit or stash your changes first." }
if (git tag --list $tag) { throw "$tag already exists. Bump versionCode and versionName in app/build.gradle.kts." }

$jdk = Get-ChildItem "$env:USERPROFILE\.jdks" -Directory -Filter 'jdk-21*' -ErrorAction SilentlyContinue | Select-Object -First 1
if ($jdk) { $env:JAVA_HOME = $jdk.FullName }
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"

& .\gradlew.bat :app:testDebugUnitTest :app:assembleRelease
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$apk = Join-Path $PSScriptRoot "app\build\outputs\apk\release\HealthBridge-$version.apk"
Copy-Item (Join-Path $PSScriptRoot 'app\build\outputs\apk\release\app-release.apk') $apk -Force
$buildTools = Get-ChildItem "$env:ANDROID_HOME\build-tools" -Directory | Sort-Object Name -Descending | Select-Object -First 1
& (Join-Path $buildTools.FullName 'apksigner.bat') verify --print-certs $apk
if ($LASTEXITCODE -ne 0) { throw "The APK isn't signed." }

git tag -a $tag -m "HealthBridge $version"
git push origin $tag
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$notes = if ($NotesFile) { @('--notes-file', $NotesFile) } else { @('--generate-notes') }
gh release create $tag $apk --title "HealthBridge $version" @notes
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Write-Host "Published $tag with $(Split-Path $apk -Leaf)."
