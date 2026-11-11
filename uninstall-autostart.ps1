$ErrorActionPreference='Stop'
$startupDir=[Environment]::GetFolderPath('Startup')
$shortcutPath=Join-Path $startupDir 'Qingzhou Procurement.lnk'
if (Test-Path -LiteralPath $shortcutPath) { Remove-Item -LiteralPath $shortcutPath;Write-Host 'Qingzhou sign-in startup removed.' }
