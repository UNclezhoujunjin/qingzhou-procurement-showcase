param([switch]$Offline, [switch]$SkipFrontendBuild)
$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
$buildRoot = Join-Path $projectRoot ('.cache/server-build/' + [guid]::NewGuid().ToString('N'))
$releaseRoot = Join-Path $projectRoot '.cache/releases/server'
Push-Location $projectRoot
try {
    if (-not $SkipFrontendBuild) {
        & npm.cmd --prefix frontend test
        if ($LASTEXITCODE -ne 0) { throw 'Frontend tests failed.' }
        & npm.cmd --prefix frontend run build
        if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed.' }
    }
    if (-not (Test-Path 'frontend/dist/index.html')) { throw 'Build the frontend first.' }
    New-Item -ItemType Directory -Path $buildRoot,$releaseRoot -Force | Out-Null
    Copy-Item -LiteralPath 'backend/pom.xml','backend/settings.xml' -Destination $buildRoot
    Copy-Item -LiteralPath 'backend/src' -Destination $buildRoot -Recurse
    $staticRoot = Join-Path $buildRoot 'src/main/resources/static'
    New-Item -ItemType Directory -Path $staticRoot -Force | Out-Null
    Copy-Item -Path 'frontend/dist/*' -Destination $staticRoot -Recurse -Force
    Push-Location $buildRoot
    try {
        $buildArgs = @('-B','-q','-s','settings.xml','-gs','settings.xml',('-Dmaven.repo.local=' + (Join-Path $projectRoot '.cache/m2')),'verify')
        if ($Offline) { $buildArgs = @('-o') + $buildArgs }
        & mvn @buildArgs
        if ($LASTEXITCODE -ne 0) { throw 'Backend verification failed.' }
    } finally { Pop-Location }
    $releaseJar = Join-Path $releaseRoot 'qingzhou.jar'
    Copy-Item -LiteralPath (Join-Path $buildRoot 'target/procurement-0.1.0.jar') -Destination $releaseJar -Force
    $checksum = (Get-FileHash -LiteralPath $releaseJar -Algorithm SHA256).Hash.ToLowerInvariant()
    [System.IO.File]::WriteAllText((Join-Path $releaseRoot 'qingzhou.jar.sha256'), "$checksum  qingzhou.jar`n", [System.Text.UTF8Encoding]::new($false))
    Write-Host "Verified server package: $releaseJar"
    Write-Host "SHA-256: $checksum"
} finally { Pop-Location }
