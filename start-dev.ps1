param([switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
$backendDir = Join-Path $projectRoot 'backend'
$frontendDir = Join-Path $projectRoot 'frontend'
$cacheDir = Join-Path $projectRoot '.cache'
New-Item -ItemType Directory -Force -Path $cacheDir | Out-Null
$repoDir = Join-Path $cacheDir 'm2'
$npmCacheDir = Join-Path $cacheDir 'npm'

foreach ($port in @(8080, 5173)) {
    $probe = New-Object System.Net.Sockets.TcpClient
    $occupied = $false
    try { $probe.Connect('127.0.0.1', $port); $occupied = $true } catch { } finally { $probe.Dispose() }
    if ($occupied) {
        throw "Port $port is already in use. Open http://127.0.0.1:5173 if Qingzhou is already running."
    }
}

Push-Location $frontendDir
try {
    if (-not (Test-Path -LiteralPath 'node_modules')) {
        & npm.cmd ci --registry=https://registry.npmjs.org "--cache=$npmCacheDir"
        if ($LASTEXITCODE -ne 0) { throw 'Frontend dependency installation failed.' }
    }
    if (-not $SkipBuild) {
        & npm.cmd run build
        if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed.' }
    }
} finally { Pop-Location }

Push-Location $backendDir
try {
    if (-not $SkipBuild) {
        & mvn -B -s settings.xml -gs settings.xml "-Dmaven.repo.local=$repoDir" verify
        if ($LASTEXITCODE -ne 0) { throw 'Backend build or tests failed.' }
    }
    if (-not (Test-Path -LiteralPath 'target/procurement-0.1.0.jar')) { throw 'Backend JAR is missing. Run without -SkipBuild.' }
} finally { Pop-Location }

$javaExe = (Get-Command java).Source
if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    $javaExe = Join-Path $env:JAVA_HOME 'bin\java.exe'
}
$nodeExe = (Get-Command node).Source
$jarPath = Join-Path $backendDir 'target/procurement-0.1.0.jar'
$vitePath = Join-Path $frontendDir 'node_modules/vite/bin/vite.js'
$backendProcess = Start-Process -FilePath $javaExe -ArgumentList @('-Dfile.encoding=UTF-8', '-jar', ('"' + $jarPath + '"')) -WorkingDirectory $backendDir -WindowStyle Hidden -RedirectStandardOutput (Join-Path $cacheDir 'backend.log') -RedirectStandardError (Join-Path $cacheDir 'backend-error.log') -PassThru
$frontendProcess = $null
try {
    $healthy = $false
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        if ($backendProcess.HasExited) { throw 'Backend exited. See .cache/backend.log.' }
        try { $health = Invoke-RestMethod 'http://127.0.0.1:8080/actuator/health' -TimeoutSec 2; if ($health.status -eq 'UP') { $healthy = $true; break } } catch { }
        Start-Sleep -Seconds 1
    }
    if (-not $healthy) { throw 'Backend did not become ready. See .cache/backend.log.' }
    $frontendProcess = Start-Process -FilePath $nodeExe -ArgumentList @(('"' + $vitePath + '"'), '--host', '127.0.0.1') -WorkingDirectory $frontendDir -WindowStyle Hidden -RedirectStandardOutput (Join-Path $cacheDir 'frontend.log') -RedirectStandardError (Join-Path $cacheDir 'frontend-error.log') -PassThru
    $frontReady = $false
    for ($attempt = 0; $attempt -lt 20; $attempt++) {
        if ($frontendProcess.HasExited) { throw 'Frontend exited. See .cache/frontend-error.log.' }
        try { if ((Invoke-WebRequest 'http://127.0.0.1:5173' -UseBasicParsing -TimeoutSec 2).StatusCode -eq 200) { $frontReady = $true; break } } catch { }
        Start-Sleep -Seconds 1
    }
    if (-not $frontReady) { throw 'Frontend did not become ready.' }
    @{backend=@{id=$backendProcess.Id; started=$backendProcess.StartTime.ToUniversalTime().Ticks; name=$backendProcess.ProcessName}; frontend=@{id=$frontendProcess.Id; started=$frontendProcess.StartTime.ToUniversalTime().Ticks; name=$frontendProcess.ProcessName}} | ConvertTo-Json | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $cacheDir 'running.json')
    Write-Host 'Qingzhou is ready: http://127.0.0.1:5173'
    Write-Host 'Demo: admin / Qingzhou@2026. Stop with ./stop.ps1'
} catch {
    if ($frontendProcess -and -not $frontendProcess.HasExited) { Stop-Process -Id $frontendProcess.Id }
    if (-not $backendProcess.HasExited) { Stop-Process -Id $backendProcess.Id }
    throw
}
