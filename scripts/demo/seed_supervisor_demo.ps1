[CmdletBinding()]
param(
  [string]$EnvironmentFile,
  [datetime]$AsOfDate = (Get-Date),
  [switch]$ResetDemoOnly
)

if ([string]::IsNullOrWhiteSpace($EnvironmentFile)) {
  $EnvironmentFile = Join-Path $PSScriptRoot '..\..\.env.demo'
}

. (Join-Path $PSScriptRoot 'demo_common.ps1')
Import-DemoEnvironment -Path $EnvironmentFile
$configuration = Get-DemoConfiguration
$asOf = $AsOfDate.Date
Assert-DemoAsOfDate -AsOfDate $asOf
$asOfIso = $asOf.ToString('yyyy-MM-dd')
$month = $asOf.ToString('yyyy-MM')
$nextMonth = $asOf.AddMonths(1).ToString('yyyy-MM')
$financialDefinition = Get-DemoFinancialDefinition -AsOfDate $asOf

function Get-AllDemoTransactions {
  param([string]$AccessToken)

  $items = @()
  $page = 0
  do {
    $response = Invoke-DemoApi -Method GET -BearerToken $AccessToken -Uri (
      "$($configuration.PlatformUrl)/api/transactions?page=$page&size=100")
    $items += @($response.items)
    $page++
  } while (-not $response.last)
  return $items
}

function Set-DemoCategory {
  param(
    [string]$AccessToken,
    [string]$TransactionId,
    [AllowNull()][object]$CategoryId
  )

  Invoke-DemoApi -Method PATCH -BearerToken $AccessToken -Uri (
    "$($configuration.PlatformUrl)/api/transactions/$TransactionId/category") -Body @{
      categoryId = $CategoryId
    } | Out-Null
}

function Get-CategoryCodeForTransaction {
  param([object]$Transaction)

  $description = [string]$Transaction.description
  if ($Transaction.direction -eq 'CREDIT') {
    if ($description -match 'SALAIRE') { return 'INCOME_SALARY' }
    if ($description -match 'EPARGNE|COURANT') { return 'TRANSFER_IN' }
    return 'INCOME_OTHER'
  }
  if ($description -match 'LOYER') { return 'HOUSING' }
  if ($description -match 'EPARGNE|COURANT') { return 'TRANSFER_OUT' }
  if ($description -match 'STEG|SONEDE|TELECOM|STREAMING') { return 'BILLS_SUBSCRIPTIONS' }
  if ($description -match 'SHELL|BOLT') { return 'TRANSPORT' }
  if ($description -match 'PHARMACIE') { return 'HEALTH' }
  if ($description -match 'HAMADI|MYTEK') { return 'SHOPPING' }
  if ($description -match 'CARREFOUR|MONOPRIX|AZIZA|GOURMANDISE|RESTAURANT|MARCHE') { return 'FOOD' }
  return 'LEISURE'
}

function Save-SalaryPlan {
  param([string]$AccessToken, [string]$PlanMonth, [object[]]$Categories)

  $amounts = $financialDefinition.ExpenseAllocations
  $overrides = @($Categories | Where-Object {
      $_.nature -eq 'EXPENSE' -and $amounts.Contains([string]$_.code)
    } | ForEach-Object {
      @{ categoryId = $_.id; confirmedAmount = (Format-DemoMoney $amounts[[string]$_.code]) }
    })
  Invoke-DemoApi -Method PUT -BearerToken $AccessToken -Uri (
    "$($configuration.PlatformUrl)/api/salary-plans/$PlanMonth`?currency=TND") -Body @{
      incomes = @(@{
          label = 'Salaire Carthage Digital Services'
          expectedAmount = (Format-DemoMoney $financialDefinition.MonthlyIncome)
        })
      allocationOverrides = $overrides
    }
}

function Ensure-DemoGoal {
  param(
    [string]$AccessToken,
    [object[]]$ExistingGoals,
    [string]$Name,
    [string]$Description,
    [string]$Type,
    [string]$TargetAmount,
    [string]$InitialAmount,
    [string]$TargetDate,
    [string]$Priority
  )

  $payload = @{
    name = $Name; description = $Description; type = $Type; currency = 'TND'
    targetAmount = $TargetAmount; initialAmount = $InitialAmount
    targetDate = $TargetDate; priority = $Priority
  }
  $existing = @($ExistingGoals | Where-Object { $_.name -eq $Name })
  if ($existing.Count -gt 1) { throw "Duplicate supervisor demo goal detected: $Name." }
  if ($existing.Count -eq 1) {
    return Invoke-DemoApi -Method PUT -BearerToken $AccessToken -Uri (
      "$($configuration.PlatformUrl)/api/goals/$($existing[0].id)") -Body $payload
  }
  return Invoke-DemoApi -Method POST -BearerToken $AccessToken -Uri (
    "$($configuration.PlatformUrl)/api/goals") -Body $payload
}

function Ensure-DemoContribution {
  param([string]$AccessToken, [object]$Goal, [string]$Amount, [string]$Date)

  $existingResponse = Invoke-DemoApi -Method GET -BearerToken $AccessToken -Uri (
    "$($configuration.PlatformUrl)/api/goals/$($Goal.id)/contributions")
  $existing = @($existingResponse)
  $match = @($existing | Where-Object {
      $_.source -eq 'MANUAL' -and [decimal]$_.amount -eq [decimal]$Amount -and $_.contributionDate -eq $Date
    })
  if ($match.Count -eq 0) {
    Invoke-DemoApi -Method POST -BearerToken $AccessToken -Uri (
      "$($configuration.PlatformUrl)/api/goals/$($Goal.id)/contributions") -Body @{
        amount = $Amount; contributionDate = $Date
      } | Out-Null
  }
}

Write-Host 'Preparing the fixed synthetic supervisor scenario (no secrets will be displayed)...'
$adminToken = Get-DemoAdminToken -Configuration $configuration

if ($ResetDemoOnly) {
  $reset = Invoke-DemoApi -Method POST -BearerToken $adminToken -Uri (
    "$($configuration.MockBankUrl)/api/admin/demo-fixtures/supervisor/reset") -Body @{
      scenarioVersion = $script:DemoIdentity.ScenarioVersion
      username = $script:DemoIdentity.BankUsername
      confirmation = 'RESET_SUPERVISOR_DEMO_ONLY'
    }
  Write-Host "Mock-bank demo fixture removed: $($reset.removed). Platform resources were not deleted."
  exit 0
}

$fixture = Invoke-DemoApi -Method POST -BearerToken $adminToken -Uri (
  "$($configuration.MockBankUrl)/api/admin/demo-fixtures/supervisor") -Body @{
    scenarioVersion = $script:DemoIdentity.ScenarioVersion
    asOfDate = $asOfIso
    customerPassword = $configuration.BankPassword
  }

$accessToken = Get-DemoAccessToken -Configuration $configuration -RegisterIfMissing
$me = Invoke-DemoApi -Method GET -BearerToken $accessToken -Uri "$($configuration.PlatformUrl)/api/auth/me"
if ($me.email -ne $script:DemoIdentity.PlatformEmail -or -not $me.emailVerified) {
  throw 'Authenticated identity is not the expected verified supervisor demo account.'
}

Invoke-DemoApi -Method PUT -BearerToken $accessToken -Uri "$($configuration.PlatformUrl)/api/profile" -Body @{
  firstName = 'Amine'; lastName = 'Ben Salem'; displayName = $script:DemoIdentity.FullName
  interfacePreferences = @{
    languageTag = $script:DemoIdentity.LanguageTag; timeZone = $script:DemoIdentity.TimeZone
    theme = 'SYSTEM'; textSize = 'NORMAL'; highContrast = $false; reducedMotion = $false
  }
  financialPreferences = @{
    primaryCurrency = 'TND'; monthStartDay = 1; lowBalanceThreshold = '500.0000'; hideHomeAmounts = $false
  }
} | Out-Null

Invoke-DemoApi -Method POST -BearerToken $accessToken -Uri (
  "$($configuration.PlatformUrl)/api/bank-connections/mock-bank") -Body @{
    username = $script:DemoIdentity.BankUsername
    password = $configuration.BankPassword
  } | Out-Null
$synchronization = Invoke-DemoApi -Method POST -BearerToken $accessToken -Uri (
  "$($configuration.PlatformUrl)/api/bank/synchronizations")
if ($synchronization.status -ne 'SUCCEEDED') { throw 'The supervisor demo bank synchronization did not succeed.' }

$categoryResponse = Invoke-DemoApi -Method GET -BearerToken $accessToken -Uri (
  "$($configuration.PlatformUrl)/api/categories")
$categories = @($categoryResponse)
$categoriesByCode = @{}
foreach ($category in $categories) { $categoriesByCode[[string]$category.code] = $category }
Invoke-DemoApi -Method POST -BearerToken $accessToken -Uri (
  "$($configuration.PlatformUrl)/api/categorization-runs") | Out-Null

$transactions = @(Get-AllDemoTransactions -AccessToken $accessToken)
$marches = @($transactions | Where-Object {
    $_.description -eq 'PAIEMENT CB MARCHE EL WARD MENZAH' -and $_.providerStatus -eq 'POSTED'
  } | Sort-Object bookingDate)
if ($marches.Count -lt 2) { throw 'Private-memory fixture transactions are missing.' }
$earlierMarche = $marches[0]
$laterMarche = $marches[$marches.Count - 1]

foreach ($transaction in $transactions) {
  if ($transaction.providerStatus -ne 'POSTED' -or $transaction.id -eq $laterMarche.id) { continue }
  if ($null -ne $transaction.categoryId) { continue }
  $code = Get-CategoryCodeForTransaction -Transaction $transaction
  if (-not $categoriesByCode.ContainsKey($code)) { throw "Required category is unavailable: $code." }
  Set-DemoCategory -AccessToken $accessToken -TransactionId $transaction.id -CategoryId $categoriesByCode[$code].id
}
Set-DemoCategory -AccessToken $accessToken -TransactionId $earlierMarche.id -CategoryId $categoriesByCode.FOOD.id

# Only the later matching merchant and excluded pending item remain eligible here;
# private memory resolves the former before any AI request is constructed.
Invoke-DemoApi -Method POST -BearerToken $accessToken -Uri (
  "$($configuration.PlatformUrl)/api/categorization-runs/merchant-recognition") | Out-Null
$suggestionResponse = Invoke-DemoApi -Method GET -BearerToken $accessToken -Uri (
  "$($configuration.PlatformUrl)/api/merchant-suggestions")
$pendingSuggestions = @($suggestionResponse)
$memorySuggestions = @($pendingSuggestions | Where-Object {
    $_.transactionId -eq $laterMarche.id -and $_.source -eq 'USER_MEMORY'
  })
if ($memorySuggestions.Count -ne 1) {
  throw 'The private USER_MEMORY suggestion is unavailable after reconciliation.'
}

$transactions = @(Get-AllDemoTransactions -AccessToken $accessToken)
$reviewDescriptions = @(
  'PAIEMENT CB GOURMANDISE LAC',
  'PAIEMENT CB HAMADI ABID ARIANA',
  'PAIEMENT CB ATELIER MEDINA MENZAH'
)
foreach ($description in $reviewDescriptions) {
  $candidate = @($transactions | Where-Object {
      $_.description -eq $description -and $_.providerStatus -eq 'POSTED'
    } | Sort-Object bookingDate -Descending | Select-Object -First 1)
  if ($candidate.Count -ne 1) { throw "Expected categorization demonstration transaction is missing: $description." }
  Set-DemoCategory -AccessToken $accessToken -TransactionId $candidate[0].id -CategoryId $null
}
if ($configuration.RunMerchantAi) {
  Invoke-DemoApi -Method POST -BearerToken $accessToken -Uri (
    "$($configuration.PlatformUrl)/api/categorization-runs/merchant-recognition") | Out-Null
}

$currentPlan = Save-SalaryPlan -AccessToken $accessToken -PlanMonth $month -Categories $categories
$nextPlan = Save-SalaryPlan -AccessToken $accessToken -PlanMonth $nextMonth -Categories $categories
if ([decimal]$currentPlan.savingCapacity -le 0 -or [decimal]$nextPlan.savingCapacity -le 0) {
  throw 'Supervisor demo Salary Plans must have a positive saving capacity.'
}

$goalResponse = Invoke-DemoApi -Method GET -BearerToken $accessToken -Uri (
  "$($configuration.PlatformUrl)/api/goals?currency=TND&includeArchived=true")
$goals = @($goalResponse)
$goalResponses = @{}
foreach ($definition in $financialDefinition.Goals) {
  $goal = Ensure-DemoGoal -AccessToken $accessToken -ExistingGoals $goals `
    -Name $definition.Name -Description $definition.Description -Type $definition.Type `
    -TargetAmount (Format-DemoMoney $definition.TargetAmount) `
    -InitialAmount (Format-DemoMoney $definition.InitialAmount) `
    -TargetDate $definition.TargetDate.ToString('yyyy-MM-dd') -Priority $definition.Priority
  $goalResponses[$definition.Name] = $goal
  Ensure-DemoContribution -AccessToken $accessToken -Goal $goal `
    -Amount (Format-DemoMoney $definition.Contribution) `
    -Date $definition.ContributionDate.ToString('yyyy-MM-dd')
}
$emergency = $goalResponses["Fonds d'urgence"]
$travel = $goalResponses['Voyage en Méditerranée']

Invoke-DemoApi -Method PUT -BearerToken $accessToken -Uri (
  "$($configuration.PlatformUrl)/api/goals/allocations/$month`?currency=TND") -Body @{
    allocations = @(
      @{ goalId = $emergency.id; amount = (Format-DemoMoney $financialDefinition.Goals[0].CurrentAllocation) },
      @{ goalId = $travel.id; amount = (Format-DemoMoney $financialDefinition.Goals[1].CurrentAllocation) }
    )
  } | Out-Null
Invoke-DemoApi -Method PUT -BearerToken $accessToken -Uri (
  "$($configuration.PlatformUrl)/api/goals/allocations/$nextMonth`?currency=TND") -Body @{
    allocations = @(
      @{ goalId = $emergency.id; amount = (Format-DemoMoney $financialDefinition.Goals[0].NextAllocation) },
      @{ goalId = $travel.id; amount = (Format-DemoMoney $financialDefinition.Goals[1].NextAllocation) }
    )
  } | Out-Null

$forecast = Invoke-DemoApi -Method POST -BearerToken $accessToken -Uri (
  "$($configuration.PlatformUrl)/api/forecasts/current?currency=TND&timezone=Africa%2FTunis")
if (-not $forecast.available) { throw "Forecast prerequisites are unavailable: $($forecast.unavailableReason)." }

Write-Host 'Supervisor demo seeded successfully.'
Write-Host "Scenario: $($fixture.scenarioVersion); as-of: $($fixture.asOfDate); transactions: $($fixture.transactionCount)."
Write-Host 'Passwords and authentication tokens were not displayed.'
