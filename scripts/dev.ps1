# Card Vault - Mobile Developer Automation Script
param (
    [string]$Action = "build"
)

$adbPath = "C:\Users\surya\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$apkPath = "app\build\outputs\apk\debug\app-debug.apk"
$package = "com.example.cardvault"
$activity = "$package/.MainActivity"

switch ($Action.ToLower()) {
    "build" {
        Write-Host "Building Card Vault Debug APK..." -ForegroundColor Cyan
        .\gradlew.bat assembleDebug
        if ($LASTEXITCODE -eq 0) {
            Write-Host "Build Successful: $apkPath" -ForegroundColor Green
        }
    }
    "run" {
        Write-Host "Compiling and deploying to emulator/device..." -ForegroundColor Cyan
        .\gradlew.bat assembleDebug
        if ($LASTEXITCODE -eq 0) {
            Write-Host "Installing APK..." -ForegroundColor Cyan
            & $adbPath install -r $apkPath
            Write-Host "Starting Activity..." -ForegroundColor Cyan
            & $adbPath shell am start -n $activity
            Write-Host "Card Vault is now running!" -ForegroundColor Green
        }
    }
    "logs" {
        Write-Host "Streaming Card Vault logs (Ctrl+C to stop)..." -ForegroundColor Yellow
        & $adbPath logcat -s CardVault:* AndroidRuntime:*
    }
    "test" {
        Write-Host "Running Unit Tests and Lint..." -ForegroundColor Cyan
        .\gradlew.bat testDebugUnitTest lintDebug
    }
    default {
        Write-Host "Usage: .\scripts\dev.ps1 [build | run | logs | test]" -ForegroundColor White
    }
}
