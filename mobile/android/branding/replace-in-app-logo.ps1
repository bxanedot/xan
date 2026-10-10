param(
    [Parameter(Mandatory = $true)]
    [string]$Png
)

$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $Png -PathType Leaf) -or
    [System.IO.Path]::GetExtension($Png).ToLowerInvariant() -ne '.png') {
    throw 'Provide an existing PNG file.'
}

$hotSwap = Join-Path $PSScriptRoot 'hotswap\android\res\drawable-nodpi\xan_in_app_mark.png'
$appResource = Join-Path $PSScriptRoot '..\app\src\main\res\drawable-nodpi\xan_in_app_mark.png'
Copy-Item -LiteralPath $Png -Destination $hotSwap -Force
Copy-Item -LiteralPath $Png -Destination $appResource -Force
Write-Host 'Updated the in-app PNG in the app and hot-swap folder. Rebuild the APK to see it.'
