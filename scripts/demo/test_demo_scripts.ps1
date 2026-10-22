Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$scripts = @(
  (Join-Path $PSScriptRoot 'demo_common.ps1'),
  (Join-Path $PSScriptRoot 'seed_supervisor_demo.ps1'),
  (Join-Path $PSScriptRoot 'verify_supervisor_demo.ps1')
)
foreach ($scriptPath in $scripts) {
  $tokens = $null
  $errors = $null
  $ast = [Management.Automation.Language.Parser]::ParseFile(
    $scriptPath, [ref]$tokens, [ref]$errors)
  if ($errors.Count -ne 0) {
    throw "PowerShell syntax validation failed for $([IO.Path]::GetFileName($scriptPath))."
  }
  if ([IO.Path]::GetFileName($scriptPath) -ne 'demo_common.ps1' -and
      $ast.ParamBlock.Extent.Text -match '\$PSScriptRoot') {
    throw "PowerShell 5.1 path resolution must not use PSScriptRoot in parameter defaults for $([IO.Path]::GetFileName($scriptPath))."
  }
}

# Windows PowerShell 5.1 does not enumerate a root JSON array returned by
# Invoke-RestMethod. Collection responses must be captured before @(...)
# expands them, otherwise consumers see one nested array instead of its items.
function Get-SyntheticCategoryResponse {
  $items = ConvertFrom-Json '[{"id":"1","code":"HOUSING"},{"id":"2","code":"FOOD"}]'
  Write-Output -NoEnumerate $items
}
$categoryResponse = Get-SyntheticCategoryResponse
$categories = @($categoryResponse)
$categoriesByCode = @{}
foreach ($category in $categories) { $categoriesByCode[[string]$category.code] = $category }
if ($categories.Count -ne 2 -or -not $categoriesByCode.ContainsKey('FOOD')) {
  throw 'PowerShell 5.1 root-array category responses were not expanded correctly.'
}
$seedSource = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'seed_supervisor_demo.ps1') -Raw
if ($seedSource -match '@\(\s*Invoke-DemoApi') {
  throw 'Seed API collection responses must be captured before array expansion on PowerShell 5.1.'
}
if ($seedSource -match '\$memoryRun\.suggestedCount') {
  throw 'Seed idempotency must verify the reconciled USER_MEMORY suggestion, not only newly created counts.'
}
$verifySource = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'verify_supervisor_demo.ps1') -Raw
if ($verifySource -match '@\(\s*Invoke-DemoApi') {
  throw 'Verification API collection responses must be captured before array expansion on PowerShell 5.1.'
}
if ($verifySource -notmatch '\$currentForecastMonth\s*=\s*"\$month-01"') {
  throw 'Forecast verification must use the LocalDate month-start shape returned by the backend DTO.'
}

. (Join-Path $PSScriptRoot 'demo_common.ps1')
$asOf = [datetime]'2026-08-21'
$definition = Get-DemoFinancialDefinition -AsOfDate $asOf
if ((Get-DemoExpectedTransactionCount -AsOfDate $asOf) -ne 165) {
  throw 'Expected transaction count is not deterministic for the reference date.'
}
if ((Get-DemoExpectedTransactionCount -AsOfDate ([datetime]'2026-10-12') -FirstCompletedMonth ([datetime]'2025-12-01')) -ne 202) {
  throw 'Rolled-over transaction counts must retain the original fixture anchor.'
}
$ledgerAccount = [pscustomobject]@{ currentBalance = '90.0000'; availableBalance = '85.0000' }
$ledgerRows = @(
  [pscustomobject]@{ amount = '100.0000'; direction = 'CREDIT'; status = 'POSTED' },
  [pscustomobject]@{ amount = '10.0000'; direction = 'DEBIT'; status = 'POSTED' },
  [pscustomobject]@{ amount = '5.0000'; direction = 'DEBIT'; status = 'PENDING' }
)
if (-not (Test-DemoLedgerBalance -Account $ledgerAccount -Transactions $ledgerRows)) {
  throw 'Ledger verification must include posted cash flow and pending debit reservations.'
}
$ledgerAccount.availableBalance = '86.0000'
if (Test-DemoLedgerBalance -Account $ledgerAccount -Transactions $ledgerRows) {
  throw 'Ledger verification must reject mismatched balances.'
}
if (-not $definition.ExpenseAllocations.Contains('FOOD')) {
  throw 'PowerShell 5.1 must be able to resolve ordered Salary Plan allocations by category code.'
}
$expenseTotal = [decimal]0
foreach ($amount in $definition.ExpenseAllocations.Values) {
  if ($amount -lt 0 -or (Format-DemoMoney $amount) -notmatch '^\d+\.\d{4}$') {
    throw 'Salary Plan allocation values must be non-negative scale-4 amounts.'
  }
  $expenseTotal += $amount
}
$capacity = $definition.MonthlyIncome - $expenseTotal
if ($capacity -le 0) { throw 'Salary Plan fixture must retain positive saving capacity.' }

$currentGoalTotal = ($definition.Goals | Measure-Object -Property CurrentAllocation -Sum).Sum
$nextGoalTotal = ($definition.Goals | Measure-Object -Property NextAllocation -Sum).Sum
if ($definition.Goals.Count -ne 2 -or $currentGoalTotal -gt $capacity -or $nextGoalTotal -gt $capacity) {
  throw 'Goal fixture allocations must fit within Salary Plan saving capacity.'
}
foreach ($goal in $definition.Goals) {
  if ($goal.TargetAmount -le $goal.InitialAmount -or $goal.Contribution -le 0 -or
      $goal.ContributionDate -gt $asOf -or $goal.TargetDate -le $asOf) {
    throw 'Goal fixture dates and progress values are inconsistent.'
  }
}

$secretNames = @('DEMO_PLATFORM_PASSWORD', 'DEMO_BANK_PASSWORD', 'DEMO_MOCK_BANK_ADMIN_PASSWORD')
$previous = @{}
foreach ($name in $secretNames) {
  $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
  [Environment]::SetEnvironmentVariable($name, '', 'Process')
}
try {
  $failedSafely = $false
  try { Assert-DemoConfiguration } catch {
    $failedSafely = $_.Exception.Message -match 'DEMO_PLATFORM_PASSWORD' -and
      $_.Exception.Message -match 'DEMO_BANK_PASSWORD'
  }
  if (-not $failedSafely) { throw 'Missing private passwords did not fail safely.' }
} finally {
  foreach ($name in $secretNames) {
    [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process')
  }
}

Write-Host 'PASS: demo PowerShell syntax, private configuration, Salary Plan and Goals fixture checks.'
