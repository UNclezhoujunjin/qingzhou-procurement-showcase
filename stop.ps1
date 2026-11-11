$ErrorActionPreference='Stop'
$cacheDir=Join-Path $PSScriptRoot '.cache'
$marker=Join-Path $cacheDir 'keep-running'
if (Test-Path -LiteralPath $marker) { Remove-Item -LiteralPath $marker }
$entries=@()
$stateFile=Join-Path $cacheDir 'running.json'
if (Test-Path -LiteralPath $stateFile) {
    $state=Get-Content -Raw -LiteralPath $stateFile | ConvertFrom-Json
    $entries+=@($state.backend,$state.frontend,$state.supervisor)
}
$supervisorFile=Join-Path $cacheDir 'supervisor.json'
if (Test-Path -LiteralPath $supervisorFile) { $entries+=Get-Content -Raw -LiteralPath $supervisorFile | ConvertFrom-Json }
foreach ($entry in $entries) {
    if (-not $entry -or -not $entry.id -or $entry.id -eq $PID) { continue }
    $process=Get-Process -Id $entry.id -ErrorAction SilentlyContinue
    if ($process -and $process.ProcessName -eq $entry.name -and $process.StartTime.ToUniversalTime().Ticks -eq $entry.started) {
        Stop-Process -Id $entry.id
        Write-Host "Stopped Qingzhou process $($entry.id)."
    }
}
foreach ($file in @($stateFile,$supervisorFile)) { if (Test-Path -LiteralPath $file) { Remove-Item -LiteralPath $file } }
