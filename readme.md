Найти процессы 



Get-Process | Where-Object {

&#x20;   $\_.ProcessName -match "emulator|qemu"

} | Select-Object Id, ProcessName, Path



Удалить процессы



Get-Process | Where-Object {

&#x20;   $\_.ProcessName -match "emulator|qemu"

} | Stop-Process -Force





Перезагрузить ADB

**$adb = "$env:LOCALAPPDATA\\Android\\Sdk\\platform-tools\\adb.exe"**



**\& $adb kill-server**

**\& $adb start-server**

**\& $adb devices**







