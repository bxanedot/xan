param(
    [Parameter(Mandatory = $true)]
    [string] $Path
)

$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($env:XAN_WINDOWS_CERTIFICATE_PFX)) {
    Write-Host 'Windows signing secrets are not configured; leaving this release MSI unsigned.'
    exit 0
}
if ([string]::IsNullOrWhiteSpace($env:XAN_WINDOWS_CERTIFICATE_PASSWORD)) {
    throw 'XAN_WINDOWS_CERTIFICATE_PASSWORD is required when a signing certificate is configured.'
}

$installer = (Resolve-Path -LiteralPath $Path).Path
$temporaryPfx = Join-Path $env:RUNNER_TEMP 'xan-release-signing.pfx'
$certificate = $null
try {
    [IO.File]::WriteAllBytes($temporaryPfx, [Convert]::FromBase64String($env:XAN_WINDOWS_CERTIFICATE_PFX))
    $password = ConvertTo-SecureString $env:XAN_WINDOWS_CERTIFICATE_PASSWORD -AsPlainText -Force
    $certificate = Import-PfxCertificate -FilePath $temporaryPfx -CertStoreLocation 'Cert:\CurrentUser\My' -Password $password
    if (-not $certificate.HasPrivateKey) { throw 'The configured PFX has no private key.' }

    $signTool = Get-ChildItem 'C:\Program Files (x86)\Windows Kits\10\bin' -Recurse -Filter signtool.exe |
        Where-Object { $_.FullName -match '\\x64\\signtool\.exe$' } |
        Sort-Object FullName -Descending |
        Select-Object -First 1
    if ($null -eq $signTool) { throw 'The Windows SDK SignTool executable was not found.' }

    & $signTool.FullName sign /fd SHA256 /td SHA256 /tr 'http://timestamp.digicert.com' /sha1 $certificate.Thumbprint $installer
    if ($LASTEXITCODE -ne 0) { throw "SignTool signing failed with exit code $LASTEXITCODE." }
    & $signTool.FullName verify /pa /all $installer
    if ($LASTEXITCODE -ne 0) { throw "SignTool could not verify the signed MSI (exit $LASTEXITCODE)." }
}
finally {
    if ($null -ne $certificate) {
        Remove-Item -LiteralPath "Cert:\CurrentUser\My\$($certificate.Thumbprint)" -Force -ErrorAction SilentlyContinue
    }
    Remove-Item -LiteralPath $temporaryPfx -Force -ErrorAction SilentlyContinue
}
