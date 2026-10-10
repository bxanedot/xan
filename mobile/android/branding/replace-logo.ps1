param(
    [Parameter(Mandatory = $true)]
    [string]$Png,
    [string]$RoundPng = $Png
)

$ErrorActionPreference = "Stop"

Add-Type -AssemblyName System.Drawing

if (-not (Test-Path -LiteralPath $Png -PathType Leaf)) {
    throw "Logo file not found: $Png"
}

if ([System.IO.Path]::GetExtension($Png).ToLowerInvariant() -ne ".png") {
    throw "XAN branding expects a PNG file."
}

$destination = Join-Path $PSScriptRoot "hotswap\android\res\drawable-nodpi\xan_mark.png"
$packagedDestination = Join-Path $PSScriptRoot "hotswap\android\res-packaged\drawable-nodpi\xan_mark.png"
$roundDestination = Join-Path $PSScriptRoot "hotswap\android\res\drawable-nodpi\xan_round_mark.png"
New-Item -ItemType Directory -Force -Path (Split-Path $destination), (Split-Path $packagedDestination) | Out-Null
if (-not (Test-Path -LiteralPath $RoundPng -PathType Leaf)) { throw "Round logo file not found: $RoundPng" }
$logoBytes = [System.IO.File]::ReadAllBytes($Png)
$roundLogoBytes = [System.IO.File]::ReadAllBytes($RoundPng)

function Save-ScaledMark {
    param(
        [Parameter(Mandatory = $true)][byte[]]$SourceBytes,
        [Parameter(Mandatory = $true)][string]$Destination,
        [Parameter(Mandatory = $true)][double]$Scale
    )

    $sourceStream = [System.IO.MemoryStream]::new($SourceBytes)
    $sourceImage = [System.Drawing.Image]::FromStream($sourceStream)
    $canvas = [System.Drawing.Bitmap]::new(
        $sourceImage.Width,
        $sourceImage.Height,
        [System.Drawing.Imaging.PixelFormat]::Format32bppArgb
    )
    $graphics = [System.Drawing.Graphics]::FromImage($canvas)

    try {
        $graphics.Clear([System.Drawing.Color]::Transparent)
        $graphics.CompositingMode = [System.Drawing.Drawing2D.CompositingMode]::SourceCopy
        $graphics.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
        $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
        $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality

        $scaledWidth = [int][Math]::Round($sourceImage.Width * $Scale)
        $scaledHeight = [int][Math]::Round($sourceImage.Height * $Scale)
        $left = [int][Math]::Floor(($canvas.Width - $scaledWidth) / 2)
        $top = [int][Math]::Floor(($canvas.Height - $scaledHeight) / 2)
        $destinationRect = [System.Drawing.Rectangle]::new($left, $top, $scaledWidth, $scaledHeight)

        $graphics.DrawImage(
            $sourceImage,
            $destinationRect,
            0,
            0,
            $sourceImage.Width,
            $sourceImage.Height,
            [System.Drawing.GraphicsUnit]::Pixel
        )

        $canvas.Save($Destination, [System.Drawing.Imaging.ImageFormat]::Png)
    } finally {
        $graphics.Dispose()
        $canvas.Dispose()
        $sourceImage.Dispose()
        $sourceStream.Dispose()
    }
}

Save-ScaledMark -SourceBytes $logoBytes -Destination $destination -Scale 0.60
Save-ScaledMark -SourceBytes $logoBytes -Destination $packagedDestination -Scale 0.60
Save-ScaledMark -SourceBytes $roundLogoBytes -Destination $roundDestination -Scale 0.60
Write-Host "XAN logo replaced:" -ForegroundColor Green
Write-Host "  $destination"
Write-Host "  $packagedDestination"
Write-Host "  $roundDestination"
Write-Host "The launcher/install mark is scaled to 60%."
Write-Host "Rebuild the APK to update the launcher/install logo."
