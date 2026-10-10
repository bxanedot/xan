param(
    [switch]$AllowMajor,
    [switch]$PatchOnly,
    [switch]$CheckOnly,
    [string]$ReportPath,
    [string]$PythonPath
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

$RepoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\.."))
$AndroidRoot = Join-Path $RepoRoot "mobile\android"
$CatalogPath = Join-Path $AndroidRoot "gradle\libs.versions.toml"
$WrapperPath = Join-Path $AndroidRoot "gradle\wrapper\gradle-wrapper.properties"

if (-not (Test-Path $CatalogPath)) {
    Write-Error "Could not find gradle\libs.versions.toml from $PSScriptRoot"
    exit 1
}

function Get-SectionText {
    param([string]$Text, [string]$Name)
    $pattern = "(?ms)^\[" + [regex]::Escape($Name) + "\]\s*(.*?)(?=^\[|\z)"
    $match = [regex]::Match($Text, $pattern)
    if ($match.Success) { return $match.Groups[1].Value }
    return ""
}

function Test-Prerelease {
    param([string]$Version)
    return $Version -match "(?i)(?:^|[-._])(?:alpha|beta|rc|dev|snapshot|preview|eap|milestone|m\d+)"
}

function Get-MajorVersion {
    param([string]$Version)
    $match = [regex]::Match($Version.TrimStart([char]"v"), "^\d+")
    if ($match.Success) { return [int64]$match.Value }
    return $null
}

function Get-StableSuffix {
    param([string]$Version)
    if (Test-Prerelease $Version) { return $null }
    $match = [regex]::Match($Version.TrimStart([char]"v"), "^\d+(?:\.\d+)*-(.+)$")
    if ($match.Success) { return $match.Groups[1].Value.ToLowerInvariant() }
    return ""
}

function Compare-Version {
    param([string]$Left, [string]$Right)

    if ($Left -eq $Right) { return 0 }

    $leftTokens = [regex]::Matches($Left.TrimStart([char]"v"), "\d+|[A-Za-z]+") | ForEach-Object { $_.Value }
    $rightTokens = [regex]::Matches($Right.TrimStart([char]"v"), "\d+|[A-Za-z]+") | ForEach-Object { $_.Value }
    $max = [Math]::Max($leftTokens.Count, $rightTokens.Count)

    for ($i = 0; $i -lt $max; $i++) {
        $hasL = $i -lt $leftTokens.Count
        $hasR = $i -lt $rightTokens.Count

        if (-not $hasL -and -not $hasR) { continue }

        if (-not $hasL) {
            if ($rightTokens[$i] -match "^[A-Za-z]+$") { return 1 }
            return -1
        }
        if (-not $hasR) {
            if ($leftTokens[$i] -match "^[A-Za-z]+$") { return -1 }
            return 1
        }

        $lNum = 0L
        $rNum = 0L
        $lIsNum = [int64]::TryParse($leftTokens[$i], [ref]$lNum)
        $rIsNum = [int64]::TryParse($rightTokens[$i], [ref]$rNum)

        if ($lIsNum -and $rIsNum) {
            if ($lNum -gt $rNum) { return 1 }
            if ($lNum -lt $rNum) { return -1 }
            continue
        }

        if ($lIsNum -and -not $rIsNum) { return 1 }
        if (-not $lIsNum -and $rIsNum) { return -1 }

        $cmp = [string]::Compare($leftTokens[$i], $rightTokens[$i], $true)
        if ($cmp -gt 0) { return 1 }
        if ($cmp -lt 0) { return -1 }
    }

    return 0
}

function Get-MinimumGradleForAgp {
    param([string]$AgpVersion)

    $match = [regex]::Match($AgpVersion, '^(\d+)\.(\d+)')
    if (-not $match.Success) { return $null }

    $line = "$($match.Groups[1].Value).$($match.Groups[2].Value)"
    switch ($line) {
        "9.4" { return "9.6.0" }
        "9.3" { return "9.5.0" }
        "9.2" { return "9.4.1" }
        "9.1" { return "9.3.1" }
        "9.0" { return "9.1.0" }
        default { return $null }
    }
}

function Get-MetadataVersions {
    param(
        [string]$BaseUrl,
        [string]$Group,
        [string]$Artifact
    )

    $groupPath = $Group.Replace(".", "/")
    $url = $BaseUrl.TrimEnd("/") + "/" + $groupPath + "/" + $Artifact + "/maven-metadata.xml"

    try {
        $response = Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 12 -Headers @{ "User-Agent" = "xan-dependency-updater/3.0" }
        [xml]$xml = $response.Content
        $versions = @($xml.metadata.versioning.versions.version | ForEach-Object { "$_".Trim() })
        return @($versions | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    }
    catch {
        if ($PythonPath) {
            try {
                $metadata = & $PythonPath (Join-Path $PSScriptRoot 'fetch_http.py') $url 2>$null
                if ($LASTEXITCODE -eq 0) {
                    [xml]$xml = $metadata -join "`n"
                    return @($xml.metadata.versioning.versions.version | ForEach-Object { "$_".Trim() })
                }
            } catch { }
        }
        return @()
    }
}

function Get-AvailableVersions {
    param(
        [string]$Type,
        [string]$Group,
        [string]$Artifact
    )

    if ($Type -eq "plugin") {
        return Get-MetadataVersions "https://plugins.gradle.org/m2" $Group $Artifact
    }

    if (
        $Group.StartsWith("androidx.") -or
        $Group.StartsWith("com.android.") -or
        $Group.StartsWith("com.google.android.gms") -or
        $Group.StartsWith("com.google.firebase") -or
        $Group.StartsWith("com.google.android.material")
    ) {
        $preferred = "https://dl.google.com/dl/android/maven2"
    }
    elseif ($Group.StartsWith("com.github.")) {
        $preferred = "https://jitpack.io"
    }
    else {
        $preferred = "https://repo1.maven.org/maven2"
    }

    $versions = @(Get-MetadataVersions $preferred $Group $Artifact)
    if ($versions.Count -gt 0) {
        return $versions
    }

    foreach ($fallback in @(
        "https://repo1.maven.org/maven2",
        "https://dl.google.com/dl/android/maven2",
        "https://jitpack.io"
    )) {
        if ($fallback -eq $preferred) { continue }
        $versions = @(Get-MetadataVersions $fallback $Group $Artifact)
        if ($versions.Count -gt 0) {
            return $versions
        }
    }

    return @()
}

function Select-LatestCompatibleVersion {
    param(
        [string]$Current,
        [string[]]$Candidates,
        [switch]$AllowMajorUpdates,
        [switch]$PatchOnly
    )

    $currentMajor = Get-MajorVersion $Current
    $currentPrerelease = Test-Prerelease $Current
    $stableSuffix = Get-StableSuffix $Current
    $best = $null
    if ($PatchOnly -and $currentPrerelease) { return $null }
    $currentParts = $Current.TrimStart([char]'v').Split('.')

    foreach ($candidate in $Candidates) {
        if ([string]::IsNullOrWhiteSpace($candidate)) { continue }
        if ((Compare-Version $candidate $Current) -le 0) { continue }
        if ($PatchOnly) {
            $candidateParts = $candidate.TrimStart([char]'v').Split('.')
            if ($candidateParts.Count -lt 3 -or $currentParts.Count -lt 3 -or
                $candidateParts[0] -ne $currentParts[0] -or $candidateParts[1] -ne $currentParts[1] -or
                (Test-Prerelease $candidate)) { continue }
        }

        $candidateMajor = Get-MajorVersion $candidate
        if (-not $AllowMajorUpdates -and $null -ne $currentMajor -and $null -ne $candidateMajor) {
            if ($candidateMajor -ne $currentMajor) { continue }
        }

        if (-not $currentPrerelease -and (Test-Prerelease $candidate)) { continue }

        if (-not [string]::IsNullOrEmpty($stableSuffix)) {
            $candidateSuffix = Get-StableSuffix $candidate
            if ($candidateSuffix -ne $stableSuffix) { continue }
        }

        if ($null -eq $best -or (Compare-Version $candidate $best) -gt 0) {
            $best = $candidate
        }
    }

    return $best
}

$text = [System.IO.File]::ReadAllText($CatalogPath)
$versionsText = Get-SectionText $text "versions"
$librariesText = Get-SectionText $text "libraries"
$pluginsText = Get-SectionText $text "plugins"

if ([string]::IsNullOrWhiteSpace($versionsText)) {
    Write-Error "Could not parse [versions] in gradle/libs.versions.toml"
    exit 1
}

$versions = @{}
foreach ($match in [regex]::Matches($versionsText, '(?m)^([A-Za-z0-9_.-]+)\s*=\s*"([^"]+)"')) {
    $versions[$match.Groups[1].Value] = $match.Groups[2].Value
}

$sources = @{}

foreach ($line in ($librariesText -split "\r?\n")) {
    $clean = ($line -split "#", 2)[0].Trim()
    if ([string]::IsNullOrWhiteSpace($clean)) { continue }

    $refMatch = [regex]::Match($clean, 'version\.ref\s*=\s*"([^"]+)"')
    if (-not $refMatch.Success) { continue }
    $key = $refMatch.Groups[1].Value
    if ($sources.ContainsKey($key)) { continue }

    $moduleMatch = [regex]::Match($clean, 'module\s*=\s*"([^":]+):([^"]+)"')
    if ($moduleMatch.Success) {
        $sources[$key] = [pscustomobject]@{
            Type = "maven"
            Group = $moduleMatch.Groups[1].Value
            Artifact = $moduleMatch.Groups[2].Value
        }
        continue
    }

    $groupMatch = [regex]::Match($clean, 'group\s*=\s*"([^"]+)"')
    $nameMatch = [regex]::Match($clean, 'name\s*=\s*"([^"]+)"')
    if ($groupMatch.Success -and $nameMatch.Success) {
        $sources[$key] = [pscustomobject]@{
            Type = "maven"
            Group = $groupMatch.Groups[1].Value
            Artifact = $nameMatch.Groups[1].Value
        }
    }
}

foreach ($line in ($pluginsText -split "\r?\n")) {
    $clean = ($line -split "#", 2)[0].Trim()
    if ([string]::IsNullOrWhiteSpace($clean)) { continue }

    $refMatch = [regex]::Match($clean, 'version\.ref\s*=\s*"([^"]+)"')
    $idMatch = [regex]::Match($clean, 'id\s*=\s*"([^"]+)"')
    if (-not $refMatch.Success -or -not $idMatch.Success) { continue }

    $key = $refMatch.Groups[1].Value
    if ($sources.ContainsKey($key)) { continue }

    $pluginId = $idMatch.Groups[1].Value
    $sources[$key] = [pscustomobject]@{
        Type = "plugin"
        Group = $pluginId
        Artifact = "$pluginId.gradle.plugin"
    }
}

$changes = @{}
$checked = 0
$failed = 0
$results = [Collections.Generic.List[object]]::new()

Write-Host "Checking $($sources.Count) version families..."
if ($AllowMajor) {
    Write-Host "Mode: major updates allowed"
} else {
    Write-Host "Mode: safe updates (same major version)"
}
Write-Host ""

foreach ($key in ($sources.Keys | Sort-Object)) {
    if (-not $versions.ContainsKey($key)) { continue }

    $current = $versions[$key]

    $source = $sources[$key]
    Write-Host -NoNewline ("  {0,-24} {1,-16} -> " -f $key, $current)

    $available = @(Get-AvailableVersions $source.Type $source.Group $source.Artifact)
    if ($available.Count -eq 0) {
        Write-Host "could not check"
        $failed++
        $results.Add([pscustomobject]@{Family=$key;Current=$current;Candidate=$null;Status='metadata unavailable; unchanged'})
        continue
    }

    $checked++
    $latest = Select-LatestCompatibleVersion -Current $current -Candidates $available -AllowMajorUpdates:$AllowMajor -PatchOnly:$PatchOnly
    $heldToolchain = $PatchOnly -and $key -in @('androidGradlePlugin','kotlin','ksp','hilt','compose','material3','adaptive')
    if ($heldToolchain) { $latest = $null }
    $results.Add([pscustomobject]@{Family=$key;Current=$current;Candidate=$latest;Status=if($heldToolchain){'toolchain held for coordinated review'}elseif($latest){'compatible candidate'}elseif($PatchOnly -and (Test-Prerelease $current)){'prerelease held for review'}else{'no automatic update'}})

    if ($null -ne $latest) {
        $changes[$key] = $latest
        Write-Host $latest -ForegroundColor Green
    } else {
        Write-Host "up to date"
    }
}

if ($ReportPath) {
    $reportDirectory = Split-Path ([IO.Path]::GetFullPath($ReportPath)) -Parent
    New-Item -ItemType Directory -Force -Path $reportDirectory | Out-Null
    ConvertTo-Json -InputObject @($results.ToArray()) -Depth 4 | Set-Content -LiteralPath $ReportPath -Encoding UTF8
}
if ($CheckOnly) { Write-Host 'Check-only mode: no versions were changed.'; exit 0 }

if ($changes.Count -eq 0) {
    Write-Host ""
    if ($failed -gt 0) {
        Write-Host "No updates found. $failed version families could not be checked." -ForegroundColor Yellow
    } else {
        Write-Host "All checked dependencies are up to date." -ForegroundColor Green
    }
    exit 0
}

if ($changes.ContainsKey("androidGradlePlugin")) {
    $targetAgp = $changes["androidGradlePlugin"]
    $requiredGradle = Get-MinimumGradleForAgp $targetAgp
    if ([string]::IsNullOrWhiteSpace($requiredGradle)) {
        Write-Host ""
        Write-Host "Skipping Android Gradle Plugin $targetAgp because its required Gradle wrapper version is not mapped yet." -ForegroundColor Yellow
        $changes.Remove("androidGradlePlugin")
    }
}

if ($changes.Count -eq 0) {
    Write-Host ""
    Write-Host "No compatible dependency updates remain after compatibility checks." -ForegroundColor Green
    exit 0
}

foreach ($key in $changes.Keys) {
    $escaped = [regex]::Escape($key)
    $pattern = '(?m)^(\s*' + $escaped + '\s*=\s*")[^"]+(".*)$'
    $replacement = '$' + '{1}' + $changes[$key] + '$' + '{2}'
    $text = [regex]::Replace($text, $pattern, $replacement)
}

[System.IO.File]::WriteAllText(
    $CatalogPath,
    $text,
    (New-Object System.Text.UTF8Encoding($false))
)

$effectiveAgp = $versions["androidGradlePlugin"]
if ($changes.ContainsKey("androidGradlePlugin")) {
    $effectiveAgp = $changes["androidGradlePlugin"]
}

$requiredGradle = Get-MinimumGradleForAgp $effectiveAgp
if (-not [string]::IsNullOrWhiteSpace($requiredGradle) -and (Test-Path $WrapperPath)) {
    $wrapperText = [System.IO.File]::ReadAllText($WrapperPath)
    $distributionMatch = [regex]::Match(
        $wrapperText,
        'gradle-([0-9][0-9A-Za-z.\-]*)-(bin|all)\.zip'
    )

    if ($distributionMatch.Success) {
        $currentGradle = $distributionMatch.Groups[1].Value
        if ((Compare-Version $requiredGradle $currentGradle) -gt 0) {
            $archiveType = $distributionMatch.Groups[2].Value
            $oldToken = $distributionMatch.Value
            $newToken = "gradle-$requiredGradle-$archiveType.zip"
            $wrapperText = $wrapperText.Replace($oldToken, $newToken)

            [System.IO.File]::WriteAllText(
                $WrapperPath,
                $wrapperText,
                (New-Object System.Text.UTF8Encoding($false))
            )

            Write-Host "Updated Gradle wrapper: $currentGradle -> $requiredGradle for AGP $effectiveAgp" -ForegroundColor Green
        }
    }
}

Write-Host ""
Write-Host "Updated $($changes.Count) version families in gradle/libs.versions.toml:" -ForegroundColor Green
foreach ($key in ($changes.Keys | Sort-Object)) {
    Write-Host "  $key : $($versions[$key]) -> $($changes[$key])"
}
if ($failed -gt 0) {
    Write-Host ""
    Write-Host "$failed version families could not be checked; they were left unchanged." -ForegroundColor Yellow
}
