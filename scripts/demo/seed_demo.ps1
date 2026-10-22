[CmdletBinding()]
param(
  [string]$EnvironmentFile,
  [datetime]$AsOfDate,
  [switch]$ResetDemoOnly
)

# Keep the existing scenario, API workflow and ownership protections unchanged.
$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'seed_supervisor_demo.ps1') @PSBoundParameters
