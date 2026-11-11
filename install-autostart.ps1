$ErrorActionPreference='Stop'
$startupDir=[Environment]::GetFolderPath('Startup')
if (-not $startupDir) { throw 'Could not locate the current user Startup folder.' }
$shortcutPath=Join-Path $startupDir 'Qingzhou Procurement.lnk'
$hostExe=Join-Path ([Environment]::SystemDirectory) 'WindowsPowerShell/v1.0/powershell.exe'
if (-not (Test-Path -LiteralPath $hostExe)) { throw 'Windows PowerShell could not be found.' }
$shell=New-Object -ComObject WScript.Shell
$shortcut=$shell.CreateShortcut($shortcutPath)
$shortcut.TargetPath=$hostExe
$shortcut.Arguments='-NoProfile -WindowStyle Hidden -File "'+(Join-Path $PSScriptRoot 'start.ps1')+'" -SkipBuild'
$shortcut.WorkingDirectory=$PSScriptRoot
$shortcut.Description='Start the local Qingzhou procurement site after sign-in.'
$shortcut.WindowStyle=7
$shortcut.Save()
$saved=$shell.CreateShortcut($shortcutPath)
if (-not $saved.TargetPath -or -not (Test-Path -LiteralPath $saved.TargetPath)) { throw 'Startup shortcut target validation failed.' }
Write-Host "Installed current-user sign-in startup: $shortcutPath"
