Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$setup = Join-Path $PSScriptRoot 'initialize_local_environment.ps1'
$repositoryRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
foreach ($templateName in @('.env.example', '.env.demo.example')) {
  $template = [IO.File]::ReadAllText((Join-Path $repositoryRoot $templateName))
  $secretEntries = [regex]::Matches($template, '(?m)^([A-Z_]*(?:PASSWORD|JWT_SECRET|API_KEY))=([^\r\n]*)')
  foreach ($entry in $secretEntries) {
    if (-not [string]::IsNullOrWhiteSpace($entry.Groups[2].Value)) {
      throw 'Public templates must not contain password, signing-key or API-key values.'
    }
  }
}
$compose = [IO.File]::ReadAllText((Join-Path $repositoryRoot 'compose.yaml'))
$publishedPorts = [regex]::Matches($compose, '(?m)^\s+- "([^"\r\n]+:\d+)"\s*$')
if ($publishedPorts.Count -ne 7) { throw 'Review the published service ports before changing local-only defaults.' }
foreach ($port in $publishedPorts) {
  if (-not $port.Groups[1].Value.StartsWith('127.0.0.1:')) {
    throw 'Local demonstration ports must bind only to loopback.'
  }
}
$testRoot = Join-Path ([IO.Path]::GetTempPath()) ('sfwa-setup-test-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $testRoot | Out-Null
try {
  $tokens = $null
  $parseErrors = $null
  $ast = [Management.Automation.Language.Parser]::ParseFile($setup, [ref]$tokens, [ref]$parseErrors)
  if ($parseErrors.Count -ne 0 -or $ast.ParamBlock.Extent.Text -match '\$PSScriptRoot') {
    throw 'Setup must parse correctly on PowerShell 5.1 without script-root parameter defaults.'
  }
  $outputs = @()
  $values = @()
  foreach ($run in 1..2) {
    $directory = Join-Path $testRoot ([string]$run)
    New-Item -ItemType Directory -Path $directory | Out-Null
    $file = Join-Path $directory '.env'
    $output = (& $setup -EnvironmentFile $file | Out-String)
    $contents = [IO.File]::ReadAllText($file)
    foreach ($key in @('DB_PASSWORD', 'MOCK_BANK_DB_PASSWORD', 'AUTH_JWT_SECRET', 'MOCK_BANK_JWT_SECRET')) {
      $match = [regex]::Match($contents, ('(?m)^' + $key + '=([^\r\n]+)'))
      if (-not $match.Success -or [Convert]::FromBase64String($match.Groups[1].Value).Length -ne 48) {
        throw 'A generated secret is missing or malformed.'
      }
      $value = $match.Groups[1].Value
      if ($output.Contains($value)) { throw 'Setup disclosed a generated secret.' }
      $values += $value
    }
    $refused = $false
    try { & $setup -EnvironmentFile $file | Out-Null } catch { $refused = $true }
    if (-not $refused -or [IO.File]::ReadAllText($file) -ne $contents) {
      throw 'Repeated setup must refuse to overwrite existing configuration.'
    }
  }
  if (@($values | Select-Object -Unique).Count -ne 8) { throw 'Setup reused a generated secret.' }
  $refused = $false
  try { & $setup -EnvironmentFile (Join-Path $testRoot 'public.txt') | Out-Null } catch { $refused = $true }
  if (-not $refused -or (Test-Path (Join-Path $testRoot 'public.txt'))) { throw 'Setup must reject a non-private filename.' }
  Write-Output 'PASS: blank public templates, loopback ports, setup syntax, independent random secrets, no secret output, overwrite refusal and private filename validation.'
} finally {
  # Only remove the explicitly created, uniquely named test directory.
  $resolved = [IO.Path]::GetFullPath($testRoot)
  $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
  if ($resolved.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase) -and
      [IO.Path]::GetFileName($resolved).StartsWith('sfwa-setup-test-')) {
    Remove-Item -LiteralPath $resolved -Recurse -Force
  }
}
