param(
    [string]$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
)

$sourceRoot = Join-Path $ProjectRoot 'mobile/android/app/src/main'
$resources = @{}
Get-ChildItem (Join-Path $sourceRoot 'res') -Recurse -File | ForEach-Object {
    if ($_.Directory.Name -match '^drawable(?:-|$)' -and -not $resources.ContainsKey($_.BaseName)) {
        $resources[$_.BaseName] = $_.FullName
    }
}

$invalid = @()
$pattern = 'painterResource\s*\(\s*(?:id\s*=\s*)?R\.drawable\.([A-Za-z0-9_]+)'
Get-ChildItem (Join-Path $sourceRoot 'kotlin') -Recurse -Filter '*.kt' | ForEach-Object {
    $sourceFile = $_.FullName
    $source = [System.IO.File]::ReadAllText($sourceFile)
    foreach ($match in [regex]::Matches($source, $pattern)) {
        $name = $match.Groups[1].Value
        if (-not $resources.ContainsKey($name)) { continue }
        $resource = $resources[$name]
        if ([System.IO.Path]::GetExtension($resource) -ne '.xml') { continue }
        try {
            [xml]$document = [System.IO.File]::ReadAllText($resource)
            $rootName = $document.DocumentElement.LocalName
        } catch {
            $invalid += "$sourceFile references unreadable XML drawable $resource"
            continue
        }
        if ($rootName -ne 'vector') {
            $invalid += "$sourceFile uses $name ($rootName XML) in painterResource"
        }
    }
}

if ($invalid.Count -gt 0) {
    $invalid | Sort-Object -Unique | ForEach-Object { Write-Error $_ }
    exit 1
}
Write-Output 'Compose drawable check passed.'
