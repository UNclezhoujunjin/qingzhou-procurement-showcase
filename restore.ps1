param([Parameter(Mandatory=$true)][string]$BackupPath,[switch]$ValidateOnly)
$ErrorActionPreference='Stop'
$backupFile=(Resolve-Path -LiteralPath $BackupPath).Path
$checksumFile=$backupFile+'.sha256'
if (-not (Test-Path -LiteralPath $checksumFile)) { throw 'Keep the .sha256 checksum file next to the backup ZIP.' }
$expected=(Get-Content -Raw -LiteralPath $checksumFile).Trim().ToLowerInvariant()
$actual=(Get-FileHash -LiteralPath $backupFile -Algorithm SHA256).Hash.ToLowerInvariant()
if ($expected -notmatch '^[a-f0-9]{64}$' -or $actual -ne $expected) { throw 'Backup checksum validation failed.' }
if (-not $ValidateOnly) {
    foreach ($port in @(5173,8080)) {
        $probe=New-Object Net.Sockets.TcpClient
        $occupied=$false
        try {$probe.Connect('127.0.0.1',$port);$occupied=$true} catch {} finally {$probe.Dispose()}
        if ($occupied) { throw 'Stop Qingzhou with ./stop.ps1 before restoring.' }
    }
    if (Test-Path -LiteralPath (Join-Path $PSScriptRoot '.cache/keep-running')) { throw 'Disable the supervisor with ./stop.ps1 before restoring.' }
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$stageDir=Join-Path $PSScriptRoot ('.cache/restore-'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $stageDir -Force | Out-Null
$stagedFile=Join-Path $stageDir 'qingzhou.mv.db'
$zip=[IO.Compression.ZipFile]::OpenRead($backupFile)
try {
    $entries=@($zip.Entries | Where-Object { [IO.Path]::GetFileName($_.FullName.Replace('\','/')) -eq 'qingzhou.mv.db' })
    if ($entries.Count -ne 1) { throw 'Expected exactly one qingzhou.mv.db entry in the backup.' }
    [IO.Compression.ZipFileExtensions]::ExtractToFile($entries[0],$stagedFile,$false)
} finally { $zip.Dispose() }
$javaExe=(Get-Command java).Source
if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin/java.exe'))) {$javaExe=Join-Path $env:JAVA_HOME 'bin/java.exe'}
$jarPath=Join-Path $PSScriptRoot 'backend/target/procurement-0.1.0.jar'
$dbBase=Join-Path $stageDir 'qingzhou'
& $javaExe '-Dfile.encoding=UTF-8' -jar $jarPath "--verify-backup-db=$dbBase"
if ($LASTEXITCODE -ne 0) { throw 'Database validation failed. The existing database was not changed.' }
if ($ValidateOnly) { Write-Host 'Backup ZIP, checksum and database are valid. No live data was changed.';exit 0 }
$dataDir=Join-Path $PSScriptRoot 'backend/data'
New-Item -ItemType Directory -Path $dataDir -Force | Out-Null
$currentFile=Join-Path $dataDir 'qingzhou.mv.db'
if (Test-Path -LiteralPath $currentFile) {
    $preserveDir=Join-Path $PSScriptRoot '.cache/backups'
    New-Item -ItemType Directory -Path $preserveDir -Force | Out-Null
    Copy-Item -LiteralPath $currentFile -Destination (Join-Path $preserveDir ('before-restore-'+(Get-Date -Format yyyyMMdd-HHmmss)+'-'+[Guid]::NewGuid().ToString('N')+'.mv.db'))
}
Copy-Item -LiteralPath $stagedFile -Destination $currentFile -Force
Write-Host 'Database restored. The previous database was preserved. Run ./start.ps1 -SkipBuild.'
