[CmdletBinding()]
param(
  [string]$EnvironmentFile,
  [datetime]$AsOfDate
)

$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'verify_supervisor_demo.ps1') @PSBoundParameters
# The existing verifier explicitly exits 0/1; preserve its result for callers.
exit $LASTEXITCODE
