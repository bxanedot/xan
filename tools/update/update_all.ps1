param([switch]$CheckOnly,[string]$PythonPath)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$androidRoot = Join-Path $repoRoot 'mobile\android'
$runDirectory = Join-Path $repoRoot ('dist\updates\'+(Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Force -Path $runDirectory | Out-Null

function Invoke-VerifiedUpdate {
    param([string[]]$Paths,[string]$BackupDirectory,[scriptblock]$Update,[scriptblock]$Verify)
    New-Item -ItemType Directory -Force -Path $BackupDirectory | Out-Null
    $backups = @{}
    foreach ($path in $Paths) {
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Missing update input: $path" }
        $backupPath = Join-Path $BackupDirectory (([guid]::NewGuid().ToString('N'))+'-'+(Split-Path $path -Leaf))
        Copy-Item -LiteralPath $path -Destination $backupPath
        $backups[$path] = $backupPath
    }
    try { & $Update; & $Verify }
    catch {
        foreach ($path in $backups.Keys) { Copy-Item -LiteralPath $backups[$path] -Destination $path -Force }
        throw
    }
}

function Get-IntegrationReport {
    param([string]$Root,[string]$RegistryPath,[string]$PythonPath)
    $registry = Get-Content -LiteralPath $RegistryPath -Raw | ConvertFrom-Json
    $results = [Collections.Generic.List[object]]::new()
    foreach ($integration in $registry) {
        $hosts = [Collections.Generic.HashSet[string]]::new()
        $found = $false
        foreach ($relative in $integration.sources) {
            $path = Join-Path $Root $relative
            if (-not (Test-Path -LiteralPath $path)) { continue }
            $found = $true
            $files = if (Test-Path -LiteralPath $path -PathType Leaf) { @(Get-Item -LiteralPath $path) } else { @(Get-ChildItem -LiteralPath $path -Recurse -File -Include '*.kt','*.kts') }
            foreach ($file in $files) {
                foreach ($match in [regex]::Matches([IO.File]::ReadAllText($file.FullName),'https://([A-Za-z0-9.-]+)')) { $hosts.Add($match.Groups[1].Value.ToLowerInvariant()) | Out-Null }
            }
        }
        if (-not $found) { $results.Add([pscustomobject]@{Integration=$integration.name;Host=$null;Status='not present; skipped'}); continue }
        if (-not $hosts.Count) { $results.Add([pscustomobject]@{Integration=$integration.name;Host=$null;Status='local integration; compile/tests required'}); continue }
        foreach ($hostname in ($hosts | Sort-Object)) {
            # Probe public service roots only. No keys, cookies, or user data are sent.
            $status = 'connection unverified'
            try {
                $response = Invoke-WebRequest -Uri ('https://'+$hostname+'/') -UseBasicParsing -Method Head -TimeoutSec 8 -MaximumRedirection 3
                $status = 'HTTP '+[int]$response.StatusCode+'; API behavior unverified'
            } catch {
                if ($_.Exception.Response) { $status = 'HTTP '+[int]$_.Exception.Response.StatusCode+'; authentication/API behavior unverified' }
                else { $status = 'network check failed; unchanged' }
                if ($PythonPath) {
                    try {
                        $probe = & $PythonPath (Join-Path $PSScriptRoot 'fetch_http.py') ('https://'+$hostname+'/') --head 2>$null
                        if ($LASTEXITCODE -eq 0) { $status = 'HTTP '+($probe | ConvertFrom-Json).status+'; API behavior unverified' }
                    } catch { }
                }
            }
            $results.Add([pscustomobject]@{Integration=$integration.name;Host=$hostname;Status=$status})
        }
    }
    return $results.ToArray()
}

try {
    Write-Host 'Checking configured integrations. These checks do not change API endpoints.'
    $integrationResults = Get-IntegrationReport -Root $repoRoot -RegistryPath (Join-Path $PSScriptRoot 'integrations.json') -PythonPath $PythonPath
    ConvertTo-Json -InputObject @($integrationResults) -Depth 4 | Set-Content -LiteralPath (Join-Path $runDirectory 'integrations.json') -Encoding UTF8
    $catalog = Join-Path $androidRoot 'gradle\libs.versions.toml'
    $wrapper = Join-Path $androidRoot 'gradle\wrapper\gradle-wrapper.properties'
    $dependencyScript = Join-Path $PSScriptRoot 'update_dependencies.ps1'
    $dependencyReport = Join-Path $runDirectory 'dependencies.json'
    $update = {
        & $dependencyScript -PatchOnly -CheckOnly:$CheckOnly -ReportPath $dependencyReport -PythonPath $PythonPath
        if ($LASTEXITCODE -ne 0) { throw 'Dependency metadata updater failed' }
    }
    if ($CheckOnly) { & $update; Write-Host "Report saved: $runDirectory"; exit 0 }
    $verify = {
        Push-Location $androidRoot
        try {
            & (Join-Path $androidRoot 'gradlew.bat') ':app:compileDebugKotlin' ':app:testDebugUnitTest' ':app:lintDebug' '--no-daemon' '--no-configuration-cache' '--console=plain' '-Pkotlin.compiler.execution.strategy=in-process'
            if ($LASTEXITCODE -ne 0) { throw 'Verification failed; dependency edits will be restored' }
            $lintReport = Join-Path $androidRoot 'app\build\reports\lint-results-debug.xml'
            if (-not (Test-Path -LiteralPath $lintReport)) { throw 'Lint XML report missing; dependency edits will be restored' }
            [xml]$lint = Get-Content -LiteralPath $lintReport -Raw
            $lintErrors = @($lint.issues.issue | Where-Object { $_.severity -in @('Error','Fatal') })
            if ($lintErrors.Count) { throw "Lint reports $($lintErrors.Count) errors; dependency edits will be restored" }
        } finally { Pop-Location }
    }
    Invoke-VerifiedUpdate -Paths @($catalog,$wrapper) -BackupDirectory (Join-Path $runDirectory 'backup') -Update $update -Verify $verify
    'Dependency edits passed compile, unit tests, and lint. Live API compatibility still requires device testing.' | Set-Content -LiteralPath (Join-Path $runDirectory 'result.txt')
    Write-Host "Verified update complete. Reports: $runDirectory"
} catch {
    ('Update failed: '+$_.Exception.Message) | Set-Content -LiteralPath (Join-Path $runDirectory 'result.txt')
    Write-Error $_
    exit 1
}

