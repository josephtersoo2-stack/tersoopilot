$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
while ($true) {
    try {
        & $adb reverse tcp:8000 tcp:8001 2>$null
        & $adb reverse tcp:8001 tcp:8001 2>$null
    } catch {}
    Start-Sleep -Seconds 3
}
