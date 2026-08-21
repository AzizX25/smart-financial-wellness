[CmdletBinding()]
param(
  [string]$EnvironmentFile,
  [datetime]$AsOfDate = (Get-Date)
)

if ([string]::IsNullOrWhiteSpace($EnvironmentFile)) {
  $EnvironmentFile = Join-Path $PSScriptRoot '..\..\.env.demo'
}

. (Join-Path $PSScriptRoot 'demo_common.ps1')
Import-DemoEnvironment -Path $EnvironmentFile
$configuration = Get-DemoConfiguration
$asOf = $AsOfDate.Date
Assert-DemoAsOfDate -AsOfDate $asOf
$month = $asOf.ToString('yyyy-MM')
$expectedTransactionCount = Get-DemoExpectedTransactionCount -AsOfDate $asOf
$results = [System.Collections.Generic.List[object]]::new()

function Add-DemoCheck {
  param([string]$Requirement, [bool]$Passed, [string]$Detail)
  $results.Add([pscustomobject]@{
      Requirement = $Requirement
      Status = if ($Passed) { 'PASS' } else { 'FAIL' }
      Detail = $Detail
    })
}

function Get-AllPlatformTransactions {
  param([string]$AccessToken)
  $all = @(); $page = 0
  do {
    $response = Invoke-DemoApi -Method GET -BearerToken $AccessToken -Uri (
      "$($configuration.PlatformUrl)/api/transactions?page=$page&size=100")
    $all += @($response.items); $page++
  } while (-not $response.last)
  return $all
}

function Get-AllMockTransactions {
  param([string]$AdminToken, [string]$AccountId)
  $all = @(); $page = 0
  do {
    $response = Invoke-DemoApi -Method GET -BearerToken $AdminToken -Uri (
      "$($configuration.MockBankUrl)/api/admin/accounts/$AccountId/transactions?page=$page&size=100")
    $all += @($response.items); $page++
  } while (-not $response.last)
  return $all
}

$accessToken = Get-DemoAccessToken -Configuration $configuration
$adminToken = Get-DemoAdminToken -Configuration $configuration
$me = Invoke-DemoApi -Method GET -BearerToken $accessToken -Uri "$($configuration.PlatformUrl)/api/auth/me"
Add-DemoCheck 'Platform login' ($me.email -eq $script:DemoIdentity.PlatformEmail -and $me.emailVerified) `
  'Fixed synthetic identity authenticated and is verified.'

$encodedUsername = [uri]::EscapeDataString($script:DemoIdentity.BankUsername)
$customerPage = Invoke-DemoApi -Method GET -BearerToken $adminToken -Uri (
  "$($configuration.MockBankUrl)/api/admin/customers?search=$encodedUsername&page=0&size=20")
$customers = @($customerPage.items | Where-Object {
    $_.username -eq $script:DemoIdentity.BankUsername -and $_.customerId -eq 'DEMO-CUST-AMINE-BEN-SALEM-V1'
  })
Add-DemoCheck 'Mock-bank customer' ($customers.Count -eq 1 -and $customers[0].status -eq 'ACTIVE') `
  'Exactly one active, explicitly synthetic customer exists.'

$accountPage = Invoke-DemoApi -Method GET -BearerToken $adminToken -Uri (
  "$($configuration.MockBankUrl)/api/admin/accounts?customerId=DEMO-CUST-AMINE-BEN-SALEM-V1&page=0&size=20")
$accounts = @($accountPage.items)
$current = @($accounts | Where-Object { $_.accountId -eq 'DEMO-TND-CURRENT-V1' })
$savings = @($accounts | Where-Object { $_.accountId -eq 'DEMO-TND-SAVINGS-V1' })
Add-DemoCheck 'Synthetic accounts' ($accounts.Count -eq 2 -and $current.Count -eq 1 -and $savings.Count -eq 1) `
  'One TND current account and one TND savings account exist.'
if ($current.Count -eq 1 -and $savings.Count -eq 1) {
  $balancesValid = [decimal]$current[0].currentBalance -ge 4000 -and [decimal]$current[0].currentBalance -le 5000 -and
    [decimal]$savings[0].currentBalance -ge 7000 -and [decimal]$savings[0].currentBalance -le 9000
  Add-DemoCheck 'Reconciled balance ranges' $balancesValid `
    'Current and savings balances are inside the documented synthetic ranges.'
}

$mockTransactions = @()
foreach ($account in $accounts) { $mockTransactions += @(Get-AllMockTransactions -AdminToken $adminToken -AccountId $account.accountId) }
$fixtureTransactions = @($mockTransactions | Where-Object { $_.transactionId -like 'SFWA-DEMO-V1-*' })
$duplicates = @($fixtureTransactions | Group-Object transactionId | Where-Object Count -gt 1)
Add-DemoCheck 'Stable transaction references' (
  $fixtureTransactions.Count -eq $expectedTransactionCount -and $duplicates.Count -eq 0) `
  "$($fixtureTransactions.Count)/$expectedTransactionCount expected fixture transactions; no duplicate reference."

$posted = @($fixtureTransactions | Where-Object status -eq 'POSTED')
$futurePosted = @($posted | Where-Object { [datetime]$_.bookingDate -gt $asOf })
$completedMonths = @($posted | ForEach-Object { ([datetime]$_.bookingDate).ToString('yyyy-MM') } |
    Where-Object { $_ -lt $month } | Sort-Object -Unique)
$moneyScaleValid = @($fixtureTransactions | Where-Object {
    $text = ([decimal]$_.amount).ToString([Globalization.CultureInfo]::InvariantCulture)
    $text.Contains('.') -and ($text.Split('.')[1].Length -gt 4)
  }).Count -eq 0
Add-DemoCheck 'Relative history and money' ($completedMonths.Count -eq 8 -and $futurePosted.Count -eq 0 -and $moneyScaleValid) `
  "$($completedMonths.Count) completed months; no future posted transaction; scale is valid."

$transferOut = @($posted | Where-Object { $_.description -eq 'VIREMENT VERS EPARGNE' })
$transferIn = @($posted | Where-Object { $_.description -eq 'VIREMENT DEPUIS COURANT' })
$pairedTransfers = $transferOut.Count -ge 8 -and $transferOut.Count -eq $transferIn.Count
if ($pairedTransfers) {
  foreach ($outgoing in $transferOut) {
    $pair = @($transferIn | Where-Object {
        $_.bookingDate -eq $outgoing.bookingDate -and [decimal]$_.amount -eq [decimal]$outgoing.amount
      })
    if ($pair.Count -ne 1) { $pairedTransfers = $false; break }
  }
}
Add-DemoCheck 'Internal transfer pairing' $pairedTransfers `
  'Current-to-savings transfers have one equal and opposite pair.'

$connection = Invoke-DemoApi -Method GET -BearerToken $accessToken -Uri (
  "$($configuration.PlatformUrl)/api/bank-connections/mock-bank")
$syncStatus = Invoke-DemoApi -Method GET -BearerToken $accessToken -Uri (
  "$($configuration.PlatformUrl)/api/bank/synchronizations/status")
$platformAccountResponse = Invoke-DemoApi -Method GET -BearerToken $accessToken -Uri (
  "$($configuration.PlatformUrl)/api/accounts")
$platformAccounts = @($platformAccountResponse)
$platformTransactions = @(Get-AllPlatformTransactions -AccessToken $accessToken)
Add-DemoCheck 'Connection and synchronization' (
  $connection.status -eq 'ACTIVE' -and $syncStatus.status -eq 'SUCCEEDED' -and
  $platformAccounts.Count -eq 2 -and $platformTransactions.Count -eq $expectedTransactionCount) `
  "$($platformAccounts.Count) owned accounts and $($platformTransactions.Count) synchronized transactions."

$profile = Invoke-DemoApi -Method GET -BearerToken $accessToken -Uri "$($configuration.PlatformUrl)/api/profile"
Add-DemoCheck 'Profile ownership and locale' (
  $profile.id -eq $me.id -and $profile.email -eq $script:DemoIdentity.PlatformEmail -and
  $profile.financialPreferences.primaryCurrency -eq 'TND' -and
  $profile.interfacePreferences.languageTag -eq 'fr-TN' -and
  $profile.interfacePreferences.timeZone -eq 'Africa/Tunis') `
  'Profile is principal-scoped with TND, French and Africa/Tunis settings.'

$salaryPlan = Invoke-DemoApi -Method GET -BearerToken $accessToken -Uri (
  "$($configuration.PlatformUrl)/api/salary-plans/$month`?currency=TND")
Add-DemoCheck 'Salary Plan' (
  $salaryPlan.currency -eq 'TND' -and [decimal]$salaryPlan.totalIncome -gt 0 -and
  [decimal]$salaryPlan.savingCapacity -gt 0) `
  'Current-month plan is internally calculated with positive saving capacity.'

$goalResponse = Invoke-DemoApi -Method GET -BearerToken $accessToken -Uri (
  "$($configuration.PlatformUrl)/api/goals?currency=TND&includeArchived=true")
$goals = @($goalResponse)
$demoGoals = @($goals | Where-Object { $_.name -in @("Fonds d'urgence", 'Voyage en Méditerranée') })
$contributionCounts = 0
foreach ($goal in $demoGoals) {
  $goalContributionResponse = Invoke-DemoApi -Method GET -BearerToken $accessToken -Uri (
    "$($configuration.PlatformUrl)/api/goals/$($goal.id)/contributions")
  $goalContributions = @($goalContributionResponse)
  $contributionCounts += $goalContributions.Count
}
$allocations = Invoke-DemoApi -Method GET -BearerToken $accessToken -Uri (
  "$($configuration.PlatformUrl)/api/goals/allocations/$month`?currency=TND")
Add-DemoCheck 'Goals, contributions and allocations' (
  $demoGoals.Count -eq 2 -and $contributionCounts -ge 2 -and $allocations.allocations.Count -eq 2 -and
  [decimal]$allocations.totalConfirmedAllocation -le [decimal]$allocations.allocatableCapacity) `
  'Two owned goals have manual progress and allocations within Salary Plan capacity.'

$forecasts = Invoke-DemoApi -Method GET -BearerToken $accessToken -Uri (
  "$($configuration.PlatformUrl)/api/forecasts?currency=TND&page=0&size=12")
$currentForecastMonth = "$month-01"
$currentForecast = @($forecasts.items | Where-Object {
    $_.forecastMonth -eq $currentForecastMonth -and $_.available
  })
Add-DemoCheck 'Forecast prerequisites' ($platformAccounts.Count -eq 2 -and $posted.Count -gt 0 -and $currentForecast.Count -ge 1) `
  'Balances, current transaction history and an available current forecast exist.'

$suggestionResponse = Invoke-DemoApi -Method GET -BearerToken $accessToken -Uri (
  "$($configuration.PlatformUrl)/api/merchant-suggestions")
$suggestions = @($suggestionResponse)
$reviews = Invoke-DemoApi -Method GET -BearerToken $accessToken -Uri (
  "$($configuration.PlatformUrl)/api/categorization-reviews?page=0&size=100")
$reviewItems = @($reviews.items)
$hasMemory = @($suggestions | Where-Object { $_.source -eq 'USER_MEMORY' }).Count -ge 1
$hasSuggestionReady = @($reviewItems | Where-Object {
    $_.description -eq 'PAIEMENT CB GOURMANDISE LAC' -and $_.reviewStatus -eq 'NEEDS_REVIEW'
  }).Count -ge 1
$hasNeedsReview = @($reviewItems | Where-Object {
    $_.description -eq 'PAIEMENT CB ATELIER MEDINA MENZAH' -and $_.reviewStatus -eq 'NEEDS_REVIEW'
  }).Count -ge 1
$hasExcluded = @($reviewItems | Where-Object {
    $_.providerStatus -eq 'PENDING' -and $_.reviewStatus -eq 'EXCLUDED'
  }).Count -ge 1
$categorized = @($platformTransactions | Where-Object { $null -ne $_.categoryId }).Count
Add-DemoCheck 'Categorization demonstration states' (
  $categorized -gt 0 -and $hasMemory -and $hasSuggestionReady -and $hasNeedsReview -and $hasExcluded) `
  'Categorized, USER_MEMORY, suggestion-ready, NEEDS_REVIEW and EXCLUDED cases exist.'

$ownershipSafe = $profile.id -eq $me.id -and $customers.Count -eq 1 -and
  @($accounts | Where-Object customerId -ne 'DEMO-CUST-AMINE-BEN-SALEM-V1').Count -eq 0
Add-DemoCheck 'Demo-only ownership isolation' $ownershipSafe `
  'All returned platform data is principal-scoped and all fixture accounts belong to the fixed synthetic customer.'

$results | Format-Table -AutoSize
$failed = @($results | Where-Object Status -eq 'FAIL')
if ($failed.Count -gt 0) {
  Write-Error "$($failed.Count) supervisor demo verification check(s) failed."
  exit 1
}
Write-Host 'All supervisor demo checks passed. No secret values were displayed.'
exit 0
