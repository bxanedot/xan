param(
    [switch]$Verify
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

$RepoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\.."))
$AndroidRoot = Join-Path $RepoRoot "mobile\android"
$CatalogPath = Join-Path $AndroidRoot "gradle\libs.versions.toml"
$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$ChangedFiles = New-Object System.Collections.Generic.List[string]
$RemovedFiles = New-Object System.Collections.Generic.List[string]

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

function Get-CatalogVersion {
    param([string]$Key)

    $text = [System.IO.File]::ReadAllText($CatalogPath)
    $match = [regex]::Match(
        $text,
        '(?m)^\s*' + [regex]::Escape($Key) + '\s*=\s*"([^"]+)"'
    )
    if ($match.Success) { return $match.Groups[1].Value }
    return $null
}

function Set-FileTextIfChanged {
    param(
        [string]$Path,
        [string]$Text
    )

    $current = [System.IO.File]::ReadAllText($Path)
    if ($current -eq $Text) { return }

    [System.IO.File]::WriteAllText($Path, $Text, $Utf8NoBom)
    $relative = $Path.Substring($RepoRoot.Length).TrimStart("\", "/")
    $ChangedFiles.Add($relative) | Out-Null
    Write-Host "  migrated $relative" -ForegroundColor Green
}

function Remove-FileIfPresent {
    param([string]$Path)

    if (-not (Test-Path $Path)) { return }

    Remove-Item -LiteralPath $Path -Force
    $relative = $Path.Substring($RepoRoot.Length).TrimStart("\", "/")
    $RemovedFiles.Add($relative) | Out-Null
    Write-Host "  removed $relative" -ForegroundColor Yellow
}

function Migrate-CommonSource {
    $sourceRoots = @(
        (Join-Path $AndroidRoot "app\src"),
        (Join-Path $AndroidRoot "modules")
    )

    # Keep every source refresh on the current XAN in-app mark. Any legacy
    # xan_logo_* reference is migrated before the old asset files are deleted.
    $legacyDrawableMap = [ordered]@{
        "xan_icon_white"   = "xan_in_app_mark"
        "xan_logo_black"   = "xan_in_app_mark"
        "xan_logo_white"   = "xan_in_app_mark"
        "xan_logo_blue"    = "xan_in_app_mark"
        "xan_logo_purple"  = "xan_in_app_mark"
        "xan_logo_pink"    = "xan_in_app_mark"
        "xan_logo_teal"    = "xan_in_app_mark"
        "xan_logo_red"     = "xan_in_app_mark"
        "xan_logo_orange"  = "xan_in_app_mark"
        "xan_logo_gradient" = "xan_in_app_mark"
        "xan_logo_neon"    = "xan_in_app_mark"
    }

    foreach ($sourceRoot in $sourceRoots) {
        if (-not (Test-Path $sourceRoot)) { continue }

        Get-ChildItem -Path $sourceRoot -Recurse -File |
            Where-Object { $_.Extension -in @(".kt", ".kts", ".xml") } |
            ForEach-Object {
                $path = $_.FullName
                $text = [System.IO.File]::ReadAllText($path)
                $updated = $text

                foreach ($legacy in $legacyDrawableMap.Keys) {
                    $current = $legacyDrawableMap[$legacy]
                    $updated = $updated.Replace("R.drawable.$legacy", "R.drawable.$current")
                    $updated = $updated.Replace("@drawable/$legacy", "@drawable/$current")
                }

                Set-FileTextIfChanged -Path $path -Text $updated
            }
    }

    # Upstream/source refreshes must not re-add retired branding resources.
    # Launcher and in-app branding are mirrored under /branding/hotswap.
    # Android consumes the in-app xan_in_app_mark from mobile/android/app/src/main/res.
    $resRoot = Join-Path $AndroidRoot "app\src\main\res"
    if (Test-Path $resRoot) {
        Get-ChildItem -Path $resRoot -Recurse -File |
            Where-Object {
                $_.BaseName -like "xan_logo_*" -or
                $_.BaseName -eq "xan_icon_white" -or
                $_.BaseName -eq "xan_mark" -or
                ($_.Extension -eq ".webp" -and $_.BaseName -like "xan_icon_*")
            } |
            ForEach-Object {
                Remove-FileIfPresent -Path $_.FullName
            }
    }
    # The daily GMS preview and its Telegram helper are not part of XAN releases.
    Remove-FileIfPresent -Path (Join-Path $RepoRoot ".github\workflows\nightly.yml")
    Remove-FileIfPresent -Path (Join-Path $RepoRoot "tools\scripts\send_telegram.py")
}

function Migrate-Material3 {
    param([string]$Version)

    if ([string]::IsNullOrWhiteSpace($Version)) { return }

    if ((Compare-Version $Version "1.5.0-alpha28") -ge 0) {
        Get-ChildItem -Path (Join-Path $AndroidRoot "app\src") -Recurse -File -Filter *.kt |
            ForEach-Object {
                $path = $_.FullName
                $text = [System.IO.File]::ReadAllText($path)
                $updated = $text
                $updated = $updated.Replace(
                    "ToggleButtonDefaults.toggleButtonColors(",
                    "ToggleButtonDefaults.colors("
                )
                $updated = $updated.Replace(
                    "sliderState.valueRange",
                    "sliderState.trackRange"
                )

                # The old rememberSliderState named argument became trackRange.
                $updated = [regex]::Replace(
                    $updated,
                    '(rememberSliderState\s*\([^\)]*?)\bvalueRange\s*=',
                    '$1trackRange =',
                    [System.Text.RegularExpressions.RegexOptions]::Singleline
                )

                Set-FileTextIfChanged -Path $path -Text $updated
            }

        $contentSettings = Join-Path $AndroidRoot "app\src\main\kotlin\app\xan\music\ui\screens\settings\ContentSettings.kt"
        if (Test-Path $contentSettings) {
            $text = [System.IO.File]::ReadAllText($contentSettings)
            $badImport = "import androidx.compose.material3.ExposedDropdownMenuBoxScope.ExposedDropdownMenu"
            $goodImport = "import androidx.compose.material3.ExposedDropdownMenu"

            $updated = $text.Replace($badImport + [Environment]::NewLine, "")

            if ($updated.Contains("ExposedDropdownMenu(") -and -not $updated.Contains($goodImport)) {
                $updated = $updated.Replace(
                    "import androidx.compose.material3.ExposedDropdownMenuBox" + [Environment]::NewLine,
                    "import androidx.compose.material3.ExposedDropdownMenuBox" + [Environment]::NewLine +
                    $goodImport + [Environment]::NewLine
                )
            }

            Set-FileTextIfChanged -Path $contentSettings -Text $updated
        }
    }
}

function Migrate-Media3 {
    param([string]$Version)

    if ([string]::IsNullOrWhiteSpace($Version)) { return }
    if ((Compare-Version $Version "1.11.0") -lt 0) { return }

    $providerPath = Join-Path $AndroidRoot "app\src\main\kotlin\app\xan\music\platform\updater\downloadmanager\XanNotificationProvider.kt"
    if (-not (Test-Path $providerPath)) { return }

    $text = [System.IO.File]::ReadAllText($providerPath)
    if ($text.Contains("override fun getNotificationChannelInfo()")) { return }

    $anchor = "    override fun handleCustomCommand(session: MediaSession, action: String, extras: Bundle): Boolean ="
    if (-not $text.Contains($anchor)) {
        throw "Media3 1.11+ requires getNotificationChannelInfo(), but XanNotificationProvider could not be migrated automatically."
    }

    $method = @"
    override fun getNotificationChannelInfo(): MediaNotification.Provider.NotificationChannelInfo =
        defaultProvider.getNotificationChannelInfo()

"@

    $text = $text.Replace($anchor, $method + $anchor)
    Set-FileTextIfChanged -Path $providerPath -Text $text
}

if (-not (Test-Path $CatalogPath)) {
    Write-Error "Could not find gradle\libs.versions.toml."
    exit 1
}

$material3Version = Get-CatalogVersion "material3"
$media3Version = Get-CatalogVersion "media3"

Write-Host ""
Write-Host "Applying XAN source compatibility migrations..."
Write-Host "  Material3: $material3Version"
Write-Host "  Media3:    $media3Version"
Write-Host ""

Migrate-CommonSource
Migrate-Material3 -Version $material3Version
Migrate-Media3 -Version $media3Version

if ($ChangedFiles.Count -eq 0 -and $RemovedFiles.Count -eq 0) {
    Write-Host "Source is already clean and compatible with the registered migrations." -ForegroundColor Green
} else {
    Write-Host ""
    Write-Host "Updated $($ChangedFiles.Count) source files and removed $($RemovedFiles.Count) retired files." -ForegroundColor Green
}

if ($Verify) {
    Write-Host ""
    Write-Host "Compile-checking Android debug..." -ForegroundColor Cyan

    $gradlew = Join-Path $AndroidRoot "gradlew.bat"
    if (-not (Test-Path $gradlew)) {
        Write-Error "gradlew.bat was not found."
        exit 1
    }

    Push-Location $AndroidRoot
    try {
        & $gradlew ":app:compileDebugKotlin" "--no-configuration-cache" "--console=plain" "--warning-mode" "summary"
        if ($LASTEXITCODE -ne 0) {
            Write-Host ""
            Write-Error "Source migration compile check failed. The updater will not mark this dependency update as safe."
            exit $LASTEXITCODE
        }
    }
    finally {
        Pop-Location
    }

    Write-Host ""
    Write-Host "Source compatibility compile check passed." -ForegroundColor Green
}
