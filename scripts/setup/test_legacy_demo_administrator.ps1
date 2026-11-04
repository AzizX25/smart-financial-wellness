Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$scriptPath = Join-Path $PSScriptRoot 'replace_legacy_demo_administrator.ps1'
$testRoot = Join-Path ([IO.Path]::GetTempPath()) ('sfwa-admin-change-test-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $testRoot | Out-Null
try {
  $backup = Join-Path $testRoot 'backup'
  New-Item -ItemType Directory -Path $backup | Out-Null
  $config = Join-Path $testRoot '.env.demo'
  $original = "DEMO_MOCK_BANK_ADMIN_USERNAME=admin.demo`nDEMO_MOCK_BANK_ADMIN_PASSWORD=synthetic-test-input`nDEMO_PLATFORM_PASSWORD=platform-test-unchanged`nDEMO_BANK_PASSWORD=bank-test-unchanged`nDEMO_CURRENCY=TND`n"
  [IO.File]::WriteAllText($config, $original)
  $refused = $false
  try { & $scriptPath -EnvironmentFile $config -BackupDirectory $backup | Out-Null } catch { $refused = $true }
  if (-not $refused -or [IO.File]::ReadAllText($config) -ne $original) { throw 'A missing matching backup must prevent mutation.' }
  Copy-Item -LiteralPath $config -Destination (Join-Path $backup '.env.demo')
  $output = & $scriptPath -EnvironmentFile $config -BackupDirectory $backup | Out-String
  $updated = [IO.File]::ReadAllText($config)
  $password = [regex]::Match($updated, '(?m)^DEMO_MOCK_BANK_ADMIN_PASSWORD=([^\r\n]+)').Groups[1].Value
  if ([Convert]::FromBase64String($password).Length -ne 36 -or $output.Contains($password)) { throw 'The replacement password must be random and never printed.' }
  foreach ($entry in @('DEMO_MOCK_BANK_ADMIN_USERNAME=local.demo.admin', 'MOCK_BANK_ADMIN_BOOTSTRAP_ENABLED=true',
      'MOCK_BANK_SPRING_PROFILES_ACTIVE=demo', 'DEMO_PLATFORM_PASSWORD=platform-test-unchanged',
      'DEMO_BANK_PASSWORD=bank-test-unchanged', 'DEMO_CURRENCY=TND')) {
    if (-not $updated.Contains($entry)) { throw 'The update changed an unrelated setting or omitted a required one.' }
  }
  $refused = $false
  try { & $scriptPath -EnvironmentFile $config -BackupDirectory $backup | Out-Null } catch { $refused = $true }
  if (-not $refused -or [IO.File]::ReadAllText($config) -ne $updated) { throw 'Repeated execution must not change the new credentials.' }
  if ([IO.File]::ReadAllText((Join-Path $backup '.env.demo')) -ne $original) { throw 'The original backup was modified.' }
  Write-Output 'PASS: matching-backup requirement, scoped private administrator replacement, no secret output, preservation of other settings and repeat refusal.'
} finally {
  $resolved = [IO.Path]::GetFullPath($testRoot)
  $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
  if ($resolved.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase) -and
      [IO.Path]::GetFileName($resolved).StartsWith('sfwa-admin-change-test-')) {
    Remove-Item -LiteralPath $resolved -Recurse -Force
  }
}
