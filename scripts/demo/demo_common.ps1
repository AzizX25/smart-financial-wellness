Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$script:DemoIdentity = [ordered]@{
  ScenarioVersion = 'supervisor-demo-v1'
  PlatformEmail = 'amine.bensalem@example.test'
  BankUsername = 'amine.bensalem.demo'
  FullName = 'Amine Ben Salem'
  Currency = 'TND'
  LanguageTag = 'fr-TN'
  TimeZone = 'Africa/Tunis'
  CountryCode = 'TN'
}

function Import-DemoEnvironment {
  param([Parameter(Mandatory = $true)][string]$Path)

  if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
    throw "Private demo configuration was not found at $Path. Copy .env.demo.example to .env.demo first."
  }
  foreach ($line in Get-Content -LiteralPath $Path -Encoding utf8) {
    $trimmed = $line.Trim()
    if ($trimmed.Length -eq 0 -or $trimmed.StartsWith('#')) { continue }
    $parts = $trimmed.Split('=', 2)
    if ($parts.Count -ne 2) { throw "Invalid demo configuration line for $($parts[0])." }
    $name = $parts[0].Trim()
    if ($name -notmatch '^[A-Z][A-Z0-9_]*$') { throw "Invalid demo configuration key: $name." }
    if ([string]::IsNullOrEmpty([Environment]::GetEnvironmentVariable($name, 'Process'))) {
      [Environment]::SetEnvironmentVariable($name, $parts[1].Trim(), 'Process')
    }
  }
}

function Get-DemoSetting {
  param([Parameter(Mandatory = $true)][string]$Name, [string]$Default = '')

  $value = [Environment]::GetEnvironmentVariable($Name, 'Process')
  if ([string]::IsNullOrWhiteSpace($value)) { return $Default }
  return $value.Trim()
}

function Assert-DemoConfiguration {
  $requiredSecrets = @('DEMO_PLATFORM_PASSWORD', 'DEMO_BANK_PASSWORD', 'DEMO_MOCK_BANK_ADMIN_PASSWORD')
  $missing = @($requiredSecrets | Where-Object { [string]::IsNullOrWhiteSpace((Get-DemoSetting -Name $_)) })
  if ($missing.Count -gt 0) {
    throw "Missing required private demo settings: $($missing -join ', ')."
  }
  foreach ($passwordName in @('DEMO_PLATFORM_PASSWORD', 'DEMO_BANK_PASSWORD')) {
    $byteCount = [Text.Encoding]::UTF8.GetByteCount((Get-DemoSetting -Name $passwordName))
    if ($byteCount -lt 12 -or $byteCount -gt 72) {
      throw "$passwordName must contain between 12 and 72 UTF-8 bytes."
    }
  }

  if ((Get-DemoSetting -Name 'DEMO_PLATFORM_EMAIL' -Default $script:DemoIdentity.PlatformEmail) -ne
      $script:DemoIdentity.PlatformEmail) {
    throw 'The platform identity must be the fixed .example.test supervisor demo account.'
  }
  if ((Get-DemoSetting -Name 'DEMO_BANK_USERNAME' -Default $script:DemoIdentity.BankUsername) -ne
      $script:DemoIdentity.BankUsername) {
    throw 'The mock-bank identity must be the fixed supervisor demo account.'
  }
  $fixedValues = @{
    DEMO_SCENARIO_VERSION = $script:DemoIdentity.ScenarioVersion
    DEMO_FULL_NAME = $script:DemoIdentity.FullName
    DEMO_CURRENCY = $script:DemoIdentity.Currency
    DEMO_LANGUAGE_TAG = $script:DemoIdentity.LanguageTag
    DEMO_TIME_ZONE = $script:DemoIdentity.TimeZone
    DEMO_COUNTRY_CODE = $script:DemoIdentity.CountryCode
  }
  foreach ($entry in $fixedValues.GetEnumerator()) {
    if ((Get-DemoSetting -Name $entry.Key -Default $entry.Value) -ne $entry.Value) {
      throw "The demo setting $($entry.Key) must keep its fixed synthetic value."
    }
  }
}

function Get-DemoConfiguration {
  Assert-DemoConfiguration
  return [ordered]@{
    PlatformUrl = (Get-DemoSetting -Name 'DEMO_PLATFORM_URL' -Default 'http://localhost:8080').TrimEnd('/')
    MockBankUrl = (Get-DemoSetting -Name 'DEMO_MOCK_BANK_URL' -Default 'http://localhost:8081').TrimEnd('/')
    MailpitUrl = (Get-DemoSetting -Name 'DEMO_MAILPIT_URL' -Default 'http://localhost:8025').TrimEnd('/')
    PlatformPassword = Get-DemoSetting -Name 'DEMO_PLATFORM_PASSWORD'
    BankPassword = Get-DemoSetting -Name 'DEMO_BANK_PASSWORD'
    AdminUsername = Get-DemoSetting -Name 'DEMO_MOCK_BANK_ADMIN_USERNAME' -Default 'local.demo.admin'
    AdminPassword = Get-DemoSetting -Name 'DEMO_MOCK_BANK_ADMIN_PASSWORD'
    RunMerchantAi = (Get-DemoSetting -Name 'DEMO_RUN_MERCHANT_AI' -Default 'false').ToLowerInvariant() -eq 'true'
  }
}

function Get-DemoFinancialDefinition {
  param([Parameter(Mandatory = $true)][datetime]$AsOfDate)

  return [pscustomobject]@{
    MonthlyIncome = [decimal]'3700.0000'
    ExpenseAllocations = [ordered]@{
      HOUSING = [decimal]'1250.0000'
      FOOD = [decimal]'820.0000'
      BILLS_SUBSCRIPTIONS = [decimal]'260.0000'
      TRANSPORT = [decimal]'260.0000'
      HEALTH = [decimal]'90.0000'
      SHOPPING = [decimal]'180.0000'
      LEISURE = [decimal]'120.0000'
      EDUCATION = [decimal]'0.0000'
      BANK_FEES = [decimal]'15.0000'
      TAXES = [decimal]'0.0000'
    }
    Goals = @(
      [pscustomobject]@{
        Name = "Fonds d'urgence"; Description = 'Réserve de sécurité pour les imprévus.'
        Type = 'EMERGENCY_FUND'; TargetAmount = [decimal]'10000.0000'
        InitialAmount = [decimal]'2500.0000'; TargetDate = $AsOfDate.Date.AddMonths(18)
        Priority = 'HIGH'; Contribution = [decimal]'500.0000'; ContributionDate = $AsOfDate.Date.AddMonths(-2)
        CurrentAllocation = [decimal]'400.0000'; NextAllocation = [decimal]'350.0000'
      },
      [pscustomobject]@{
        Name = 'Voyage en Méditerranée'; Description = 'Projet de voyage planifié sans crédit.'
        Type = 'TRAVEL'; TargetAmount = [decimal]'4500.0000'
        InitialAmount = [decimal]'800.0000'; TargetDate = $AsOfDate.Date.AddMonths(12)
        Priority = 'MEDIUM'; Contribution = [decimal]'250.0000'; ContributionDate = $AsOfDate.Date.AddMonths(-1)
        CurrentAllocation = [decimal]'200.0000'; NextAllocation = [decimal]'200.0000'
      }
    )
  }
}

function Format-DemoMoney {
  param([Parameter(Mandatory = $true)][decimal]$Amount)
  return $Amount.ToString('0.0000', [Globalization.CultureInfo]::InvariantCulture)
}

function Assert-DemoAsOfDate {
  param([Parameter(Mandatory = $true)][datetime]$AsOfDate)

  $today = (Get-Date).Date
  if ($AsOfDate.Date -gt $today -or $AsOfDate.ToString('yyyy-MM') -ne $today.ToString('yyyy-MM')) {
    throw 'The live demo as-of date must be today or an earlier date in the current calendar month.'
  }
}

function Get-DemoExpectedTransactionCount {
  param(
    [Parameter(Mandatory = $true)][datetime]$AsOfDate,
    [datetime]$FirstCompletedMonth = [datetime]::MinValue
  )

  $currentMonth = $AsOfDate.Date.AddDays(1 - $AsOfDate.Day)
  if ($FirstCompletedMonth -eq [datetime]::MinValue) { $FirstCompletedMonth = $currentMonth.AddMonths(-8) }
  $completedMonths = ($currentMonth.Year - $FirstCompletedMonth.Year) * 12 +
    $currentMonth.Month - $FirstCompletedMonth.Month
  if ($FirstCompletedMonth.Day -ne 1 -or $completedMonths -lt 8) {
    throw 'The demo history anchor must be a month start with at least eight completed months.'
  }
  # Original opening entries and memory example remain fixed. Review cases are
  # appended each month from the original demonstration month onward.
  $baseCount = 2 + ($completedMonths * 18) + 1 + (($completedMonths - 7) * 3)
  $currentScheduleDays = @(2, 3, 5, 6, 6, 8, 9, 10, 11, 12, 14, 16, 18, 20, 21, 22, 24, 27)
  return $baseCount + @($currentScheduleDays | Where-Object { $_ -le $AsOfDate.Day }).Count
}

function Test-DemoLedgerBalance {
  param([Parameter(Mandatory = $true)][object]$Account, [object[]]$Transactions)

  [decimal]$current = 0
  [decimal]$available = 0
  foreach ($transaction in $Transactions) {
    [decimal]$amount = $transaction.amount
    if ($transaction.status -eq 'POSTED') {
      $signed = if ($transaction.direction -eq 'CREDIT') { $amount } else { -$amount }
      $current += $signed
      $available += $signed
    } elseif ($transaction.status -eq 'PENDING' -and $transaction.direction -eq 'DEBIT') {
      $available -= $amount
    }
  }
  return $current -eq [decimal]$Account.currentBalance -and $available -eq [decimal]$Account.availableBalance
}

function Invoke-DemoApi {
  param(
    [Parameter(Mandatory = $true)][ValidateSet('GET', 'POST', 'PUT', 'PATCH', 'DELETE')][string]$Method,
    [Parameter(Mandatory = $true)][string]$Uri,
    [object]$Body,
    [string]$BearerToken
  )

  $headers = @{}
  if (-not [string]::IsNullOrWhiteSpace($BearerToken)) {
    $headers.Authorization = "Bearer $BearerToken"
  }
  $parameters = @{
    Method = $Method
    Uri = $Uri
    Headers = $headers
    ErrorAction = 'Stop'
  }
  if ($PSBoundParameters.ContainsKey('Body')) {
    $parameters.ContentType = 'application/json'
    $parameters.Body = ($Body | ConvertTo-Json -Depth 12 -Compress)
  }
  try {
    return Invoke-RestMethod @parameters
  } catch {
    $status = 'unavailable'
    if ($null -ne $_.Exception.Response -and $null -ne $_.Exception.Response.StatusCode) {
      $status = [int]$_.Exception.Response.StatusCode
    }
    throw "Demo API request failed: $Method $Uri returned HTTP $status."
  }
}

function Try-DemoLogin {
  param([System.Collections.IDictionary]$Configuration)

  try {
    return Invoke-DemoApi -Method POST -Uri "$($Configuration.PlatformUrl)/api/auth/login" -Body @{
      email = $script:DemoIdentity.PlatformEmail
      password = $Configuration.PlatformPassword
    }
  } catch {
    return $null
  }
}

function Assert-DemoPlatformIdentity {
  param([Parameter(Mandatory = $true)][object]$User)

  if ($User.email -ne $script:DemoIdentity.PlatformEmail -or
      $User.firstName -ne 'Amine' -or $User.lastName -ne 'Ben Salem' -or
      $User.displayName -ne $script:DemoIdentity.FullName) {
    throw 'Existing platform identity does not match the fixed supervisor demo persona.'
  }
}

function Get-DemoVerificationToken {
  param([System.Collections.IDictionary]$Configuration)

  $encodedEmail = [uri]::EscapeDataString($script:DemoIdentity.PlatformEmail)
  $deadline = [DateTimeOffset]::UtcNow.AddSeconds(20)
  do {
    try {
      $search = Invoke-RestMethod -Method GET -Uri "$($Configuration.MailpitUrl)/api/v1/search?query=to:$encodedEmail" -ErrorAction Stop
      $messages = @($search.messages | Sort-Object Created -Descending)
      foreach ($message in $messages) {
        $messageId = if ($message.ID) { $message.ID } else { $message.Id }
        if ([string]::IsNullOrWhiteSpace($messageId)) { continue }
        $content = Invoke-RestMethod -Method GET -Uri "$($Configuration.MailpitUrl)/api/v1/message/$messageId" -ErrorAction Stop
        $combined = "$($content.Text)`n$($content.HTML)"
        $match = [regex]::Match($combined, '#token=([A-Za-z0-9._~%+-]+)')
        if ($match.Success) { return [uri]::UnescapeDataString($match.Groups[1].Value) }
      }
    } catch {
      # Mail delivery is asynchronous; retry without exposing response content.
    }
    Start-Sleep -Milliseconds 500
  } while ([DateTimeOffset]::UtcNow -lt $deadline)
  throw 'No verification message was found in local Mailpit for the fixed demo identity.'
}

function Get-DemoAccessToken {
  param([System.Collections.IDictionary]$Configuration, [switch]$RegisterIfMissing)

  $login = Try-DemoLogin -Configuration $Configuration
  if ($null -ne $login) { Assert-DemoPlatformIdentity -User $login.user }
  if ($null -ne $login -and $login.user.emailVerified) { return $login.accessToken }
  if (-not $RegisterIfMissing) { throw 'The fixed supervisor demo platform account cannot authenticate.' }

  if ($null -eq $login) {
    try {
      Invoke-DemoApi -Method POST -Uri "$($Configuration.PlatformUrl)/api/auth/register" -Body @{
        email = $script:DemoIdentity.PlatformEmail
        password = $Configuration.PlatformPassword
        firstName = 'Amine'
        lastName = 'Ben Salem'
        displayName = $script:DemoIdentity.FullName
      } | Out-Null
    } catch {
      throw 'The fixed demo identity exists but cannot be verified with the supplied private password.'
    }
  } else {
    Invoke-DemoApi -Method POST -Uri "$($Configuration.PlatformUrl)/api/auth/email-verification/resend" -Body @{
      email = $script:DemoIdentity.PlatformEmail
    } | Out-Null
  }

  $verificationToken = Get-DemoVerificationToken -Configuration $Configuration
  Invoke-DemoApi -Method POST -Uri "$($Configuration.PlatformUrl)/api/auth/email-verification/confirm" -Body @{
    token = $verificationToken
  } | Out-Null
  $login = Try-DemoLogin -Configuration $Configuration
  if ($null -eq $login -or -not $login.user.emailVerified) {
    throw 'The fixed demo identity exists but cannot be verified with the supplied private password.'
  }
  Assert-DemoPlatformIdentity -User $login.user
  return $login.accessToken
}

function Get-DemoAdminToken {
  param([System.Collections.IDictionary]$Configuration)

  $response = Invoke-DemoApi -Method POST -Uri "$($Configuration.MockBankUrl)/api/admin/auth/login" -Body @{
    username = $Configuration.AdminUsername
    password = $Configuration.AdminPassword
  }
  return $response.accessToken
}
