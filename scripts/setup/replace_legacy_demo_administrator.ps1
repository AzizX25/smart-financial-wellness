[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)][string]$BackupDirectory,
  [string]$EnvironmentFile
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
if ([string]::IsNullOrWhiteSpace($EnvironmentFile)) {
  $EnvironmentFile = Join-Path $repositoryRoot '.env.demo'
}
$destination = [IO.Path]::GetFullPath($EnvironmentFile)
if ([IO.Path]::GetFileName($destination) -ne '.env.demo') {
  throw 'Only the private .env.demo configuration can be updated.'
}
$backup = Join-Path ([IO.Path]::GetFullPath($BackupDirectory)) '.env.demo'
if (-not (Test-Path -LiteralPath $backup -PathType Leaf) -or
    (Get-FileHash -LiteralPath $backup -Algorithm SHA256).Hash -ne
    (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash) {
  throw 'A matching protected configuration backup is required before changing the legacy administrator.'
}
$contents = [IO.File]::ReadAllText($destination)
$usernameMatches = [regex]::Matches($contents, '(?m)^DEMO_MOCK_BANK_ADMIN_USERNAME=([^\r\n]*)')
if ($usernameMatches.Count -ne 1 -or $usernameMatches[0].Groups[1].Value.Trim() -ne 'admin.demo') {
  throw 'This command only replaces the legacy administrator configuration; nothing was changed.'
}
foreach ($key in @('DEMO_MOCK_BANK_ADMIN_USERNAME', 'DEMO_MOCK_BANK_ADMIN_PASSWORD',
    'MOCK_BANK_ADMIN_BOOTSTRAP_ENABLED', 'MOCK_BANK_SPRING_PROFILES_ACTIVE')) {
  if ([regex]::Matches($contents, ('(?m)^' + $key + '=')).Count -gt 1) {
    throw 'Duplicate configuration keys must be resolved privately before proceeding.'
  }
}

# The operator must first confirm this separate username is unused in the bank.
$rng = [Security.Cryptography.RandomNumberGenerator]::Create()
try {
  $bytes = New-Object byte[] 36
  $rng.GetBytes($bytes)
  $replacement = [ordered]@{
    DEMO_MOCK_BANK_ADMIN_USERNAME = 'local.demo.admin'
    DEMO_MOCK_BANK_ADMIN_PASSWORD = [Convert]::ToBase64String($bytes)
    MOCK_BANK_ADMIN_BOOTSTRAP_ENABLED = 'true'
    MOCK_BANK_SPRING_PROFILES_ACTIVE = 'demo'
  }
} finally { $rng.Dispose() }
foreach ($key in $replacement.Keys) {
  $pattern = '(?m)^' + [regex]::Escape($key) + '=[^\r\n]*'
  $entry = $key + '=' + $replacement[$key]
  if ([regex]::IsMatch($contents, $pattern)) {
    $contents = [regex]::Replace($contents, $pattern, $entry)
  } else {
    $contents = $contents.TrimEnd("`r", "`n") + "`n" + $entry + "`n"
  }
}

# Atomically replace only this private file. Never put a secret in a command argument.
$temporary = $destination + '.' + [guid]::NewGuid().ToString('N') + '.tmp'
$replacementBackup = Join-Path ([IO.Path]::GetFullPath($BackupDirectory)) '.env.demo.before-replacement'
if (Test-Path -LiteralPath $replacementBackup) {
  throw 'A prior replacement backup already exists; no private configuration was changed.'
}
try {
  [IO.File]::WriteAllText($temporary, $contents, (New-Object Text.UTF8Encoding($false)))
  [IO.File]::Replace($temporary, $destination, $replacementBackup)
} finally {
  if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Force }
}
Write-Output 'Private demo administrator configuration replaced. Other passwords are unchanged. Start the corrected mock bank normally to provision the new account and apply V5.'
