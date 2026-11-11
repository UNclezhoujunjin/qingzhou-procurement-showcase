$ErrorActionPreference='Stop'
$projectRoot=$PSScriptRoot
$cacheDir=Join-Path $projectRoot '.cache'
$marker=Join-Path $cacheDir 'keep-running'
if (-not (Test-Path -LiteralPath $marker)) { exit 0 }
$javaExe=(Get-Command java).Source
if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin/java.exe'))) { $javaExe=Join-Path $env:JAVA_HOME 'bin/java.exe' }
$jarPath=Join-Path $projectRoot 'backend/target/procurement-0.1.0.jar'
$distPath=(Join-Path $projectRoot 'frontend/dist') + [IO.Path]::DirectorySeparatorChar
$env:PORT='5173'
$env:STATIC_LOCATIONS=(New-Object System.Uri($distPath)).AbsoluteUri
$self=Get-Process -Id $PID
$supervisorState=@{id=$PID;started=$self.StartTime.ToUniversalTime().Ticks;name=$self.ProcessName}
$supervisorState | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $cacheDir 'supervisor.json') -Encoding UTF8
$failures=0
while (Test-Path -LiteralPath $marker) {
    try {
        Write-Output "$(Get-Date -Format s) Starting Qingzhou."
        if (Test-Path -LiteralPath (Join-Path $cacheDir 'backend.log')) { Copy-Item -LiteralPath (Join-Path $cacheDir 'backend.log') -Destination (Join-Path $cacheDir 'backend.previous.log') -Force }
        $child=Start-Process -FilePath $javaExe -ArgumentList @('-Dfile.encoding=UTF-8','-Dserver.address=127.0.0.1','-Dapp.runtime-mode=stable',('-Dspring.web.resources.static-locations='+$env:STATIC_LOCATIONS),'-jar',('"'+$jarPath+'"')) -WorkingDirectory (Join-Path $projectRoot 'backend') -WindowStyle Hidden -RedirectStandardOutput (Join-Path $cacheDir 'backend.log') -RedirectStandardError (Join-Path $cacheDir 'backend-error.log') -PassThru
        $state=@{backend=@{id=$child.Id;started=$child.StartTime.ToUniversalTime().Ticks;name=$child.ProcessName};supervisor=$supervisorState}
        $state | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $cacheDir 'running.next.json') -Encoding UTF8
        Move-Item -LiteralPath (Join-Path $cacheDir 'running.next.json') -Destination (Join-Path $cacheDir 'running.json') -Force
        $child.WaitForExit()
        if (-not (Test-Path -LiteralPath $marker)) { break }
        Write-Output "$(Get-Date -Format s) Backend exited; restarting in 3 seconds."
        $failures++
        Start-Sleep -Seconds ([Math]::Min(30,3*$failures))
    } catch {
        Write-Output "$(Get-Date -Format s) Supervisor error: $($_.Exception.Message)"
        Start-Sleep -Seconds 10
    }
}
