[CmdletBinding()]
param([string]$EnvironmentFile)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# Resolve after param binding for Windows PowerShell 5.1 compatibility.
$repositoryRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
if ([string]::IsNullOrWhiteSpace($EnvironmentFile)) {
  $EnvironmentFile = Join-Path $repositoryRoot '.env'
}
$destination = [IO.Path]::GetFullPath($EnvironmentFile)
if ([IO.Path]::GetFileName($destination) -ne '.env') {
  throw 'The private configuration filename must be .env.'
}
if (Test-Path -LiteralPath $destination) {
  throw 'Private configuration already exists. Nothing was overwritten; existing credentials require a deliberate rotation procedure.'
}

$template = [IO.File]::ReadAllText((Join-Path $repositoryRoot '.env.example'))
$rng = [Security.Cryptography.RandomNumberGenerator]::Create()
try {
  foreach ($name in @('DB_PASSWORD', 'MOCK_BANK_DB_PASSWORD', 'AUTH_JWT_SECRET', 'MOCK_BANK_JWT_SECRET')) {
    $bytes = New-Object byte[] 48
    $rng.GetBytes($bytes)
    $value = [Convert]::ToBase64String($bytes)
    $pattern = '(?m)^' + [regex]::Escape($name) + '=[^\r\n]*'
    if ([regex]::Matches($template, $pattern).Count -ne 1) {
      throw 'The configuration template must contain exactly one entry for every required secret.'
    }
    $template = [regex]::Replace($template, $pattern, ($name + '=' + $value))
  }
} finally {
  $rng.Dispose()
}

# CreateNew also refuses an overwrite if another process wins the existence race.
$stream = $null
try {
  $stream = [IO.File]::Open($destination, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
  $encoded = (New-Object Text.UTF8Encoding($false)).GetBytes($template)
  $stream.Write($encoded, 0, $encoded.Length)
} finally {
  if ($null -ne $stream) { $stream.Dispose() }
}
Write-Output 'Private .env created with four independent random secrets. No values were displayed. Keep this file private and backed up securely.'
