# Install debug APK to a USB-connected phone and relaunch the app.
# Plain "adb install -r" replaces the APK but does not restart a running process.
$ErrorActionPreference = "Stop"

$AppId = "com.example.fuelmonitor"
$Activity = "$AppId/.MainActivity"
$Apk = Join-Path $PSScriptRoot "app\build\outputs\apk\debug\app-debug.apk"

if (-not (Test-Path $Apk)) {
    Write-Error "APK not found at $Apk. Run .\gradlew.bat assembleDebug first."
}

Write-Host "Stopping $AppId..."
adb shell am force-stop $AppId

Write-Host "Installing $Apk..."
adb install -r $Apk
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

Write-Host "Starting $Activity..."
adb shell am start -n $Activity
