param([switch]$Apply,[string]$Profile,[int]$TargetSdk,[string]$PythonPath)
$ErrorActionPreference = 'Stop'
if (-not $PythonPath) {
    $bundled = Join-Path $env:USERPROFILE '.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe'
    if (Test-Path -LiteralPath $bundled) { $PythonPath = $bundled }
    else { $PythonPath = (Get-Command python.exe -ErrorAction Stop).Source }
}
$arguments = @((Join-Path $PSScriptRoot 'android_toolchain.py'))
if ($Apply) { $arguments += '--apply' }
if ($Profile) { $arguments += @('--profile', $Profile) }
if ($TargetSdk) { $arguments += @('--target-sdk', "$TargetSdk") }
& $PythonPath @arguments
exit $LASTEXITCODE
