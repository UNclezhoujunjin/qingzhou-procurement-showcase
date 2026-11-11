param([switch]$SkipBuild, [switch]$Dev)
$ErrorActionPreference = 'Stop'
if ($Dev) { & (Join-Path $PSScriptRoot 'start-dev.ps1') -SkipBuild:$SkipBuild; exit $LASTEXITCODE }
$projectRoot = $PSScriptRoot
$cacheDir = Join-Path $projectRoot '.cache'
New-Item -ItemType Directory -Path $cacheDir -Force | Out-Null
$probe = New-Object System.Net.Sockets.TcpClient
$occupied = $false
try { $probe.Connect('127.0.0.1',5173); $occupied=$true } catch { } finally { $probe.Dispose() }
if ($occupied) {
    try {
        $config=Invoke-RestMethod 'http://127.0.0.1:5173/api/auth/config' -TimeoutSec 3
        if ($config.application -eq 'qingzhou-procurement') { Write-Host 'Qingzhou is already running: http://127.0.0.1:5173'; exit 0 }
    } catch { }
    throw 'Port 5173 is in use by another application.'
}
if (-not $SkipBuild) {
    Push-Location (Join-Path $projectRoot 'frontend')
    try {
        if (-not (Test-Path -LiteralPath 'node_modules')) {
            & npm.cmd ci --registry=https://registry.npmjs.org "--cache=$(Join-Path $cacheDir 'npm')"
            if ($LASTEXITCODE -ne 0) { throw 'Frontend dependencies failed.' }
        }
        & npm.cmd run build
        if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed.' }
    } finally { Pop-Location }
    Push-Location (Join-Path $projectRoot 'backend')
    try {
        & mvn -B -s settings.xml -gs settings.xml "-Dmaven.repo.local=$(Join-Path $cacheDir 'm2')" verify
        if ($LASTEXITCODE -ne 0) { throw 'Backend build or tests failed.' }
    } finally { Pop-Location }
}
if (-not (Test-Path -LiteralPath (Join-Path $projectRoot 'frontend/dist/index.html'))) { throw 'Build the frontend first (run without -SkipBuild).' }
if (-not (Test-Path -LiteralPath (Join-Path $projectRoot 'backend/target/procurement-0.1.0.jar'))) { throw 'Build the backend first.' }
$marker=Join-Path $cacheDir 'keep-running'
Set-Content -LiteralPath $marker -Value 'Qingzhou local supervisor is enabled.' -Encoding UTF8
$hostExe=[System.Diagnostics.Process]::GetCurrentProcess().MainModule.FileName
$supervisorScript=Join-Path $projectRoot 'supervise.ps1'
$supervisor=Start-Process -FilePath $hostExe -ArgumentList @('-NoProfile','-File',('"'+$supervisorScript+'"')) -WorkingDirectory $projectRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $cacheDir 'supervisor.log') -RedirectStandardError (Join-Path $cacheDir 'supervisor-error.log') -PassThru
$ready=$false
for ($attempt=0;$attempt -lt 60;$attempt++) {
    if ($supervisor.HasExited) { throw 'Supervisor exited. See .cache/supervisor-error.log.' }
    try { $health=Invoke-RestMethod 'http://127.0.0.1:5173/actuator/health' -TimeoutSec 2; if ($health.status -eq 'UP') { $ready=$true;break } } catch { }
    Start-Sleep -Seconds 1
}
if (-not $ready) { throw 'Service did not become ready. See .cache/backend.log and .cache/supervisor.log.' }
Write-Host 'Qingzhou stable local site: http://127.0.0.1:5173'
Write-Host 'The supervisor restarts the backend after unexpected exits. Stop with ./stop.ps1.'
