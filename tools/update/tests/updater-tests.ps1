$ErrorActionPreference = 'Stop'
$updaterDirectory = Split-Path $PSScriptRoot -Parent
foreach ($name in @('update_dependencies.ps1','update_all.ps1')) {
    $tokens = $null; $errors = $null
    $path = Join-Path $updaterDirectory $name
    if (-not (Test-Path $path)) { continue }
    $ast = [Management.Automation.Language.Parser]::ParseFile($path,[ref]$tokens,[ref]$errors)
    if ($errors.Count) { throw "Parse errors in $name" }
    foreach ($function in $ast.FindAll({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst]},$false)) {
        Invoke-Expression $function.Extent.Text
    }
}
function Assert-Equal($actual,$expected,$message) {
    if ($actual -ne $expected) { throw "$message; expected '$expected', received '$actual'" }
}
Assert-Equal (Select-LatestCompatibleVersion '1.2.3' @('1.2.4','1.3.0','2.0.0','1.2.5-beta1') -PatchOnly) '1.2.4' 'Only a stable patch is automatic'
Assert-Equal (Select-LatestCompatibleVersion '0.26.5' @('0.27.0','0.26.6') -PatchOnly) '0.26.6' 'Pre-1.0 minor versions are held'
Assert-Equal (Select-LatestCompatibleVersion '1.5.0-alpha29' @('1.5.0-alpha30','1.5.0') -PatchOnly) $null 'Prerelease families require manual review'
Assert-Equal (Select-LatestCompatibleVersion '3.0.0' @('2.9.0','3.0.0') -PatchOnly) $null 'Never downgrade'
$fixture = Join-Path ([IO.Path]::GetTempPath()) ('xan-update-test-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $fixture | Out-Null
$catalog = Join-Path $fixture 'catalog.toml'; $wrapper = Join-Path $fixture 'wrapper.properties'
[IO.File]::WriteAllText($catalog,'user-edited catalog'); [IO.File]::WriteAllText($wrapper,'user-edited wrapper')
try {
    $failed = $false
    try { Invoke-VerifiedUpdate -Paths @($catalog,$wrapper) -BackupDirectory (Join-Path $fixture 'backup') -Update { [IO.File]::WriteAllText($catalog,'candidate'); [IO.File]::WriteAllText($wrapper,'candidate wrapper') } -Verify { throw 'compiler rejected candidate' } }
    catch { $failed = $true }
    Assert-Equal $failed $true 'Verification failure is reported'
    Assert-Equal ([IO.File]::ReadAllText($catalog)) 'user-edited catalog' 'Catalog restored exactly'
    Assert-Equal ([IO.File]::ReadAllText($wrapper)) 'user-edited wrapper' 'Wrapper restored exactly'
    Invoke-VerifiedUpdate -Paths @($catalog) -BackupDirectory (Join-Path $fixture 'success') -Update { [IO.File]::WriteAllText($catalog,'verified update') } -Verify { }
    Assert-Equal ([IO.File]::ReadAllText($catalog)) 'verified update' 'Successful verification keeps changes'
} finally {
    $resolvedFixture = [IO.Path]::GetFullPath($fixture)
    if (-not $resolvedFixture.StartsWith([IO.Path]::GetTempPath()) -or (Split-Path $resolvedFixture -Leaf) -notlike 'xan-update-test-*') { throw 'Unsafe test fixture path' }
    Remove-Item -LiteralPath $resolvedFixture -Recurse -Force
}
Write-Host 'PASS: updater version policy and rollback checks'
