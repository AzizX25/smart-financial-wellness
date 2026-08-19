import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react'
import { getBankConnection } from '../services/bankApi'
import { ApiError } from '../services/apiClient'
import {
  getDashboardSummary,
  getSynchronizationStatus,
  listLocalAccounts,
  listLocalTransactions,
  startSynchronization,
} from '../services/financialApi'

const CONNECTION_REQUIRED = 'A mock-bank connection is required'
const FinancialDataContext = createContext(null)

function currentMonth() {
  const now = new Date()
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`
}

function controlledFinancialError(error) {
  if (!(error instanceof ApiError)) return 'Une erreur inattendue est survenue.'
  if (error.status === 0) return 'Le service financier est momentanément inaccessible.'
  if (error.status === 503) return 'La banque simulée est momentanément indisponible.'
  if (error.status === 502) return 'La synchronisation bancaire n’a pas pu être finalisée.'
  if (error.status === 409 && error.message === 'BANK_AUTHORIZATION_EXPIRED') {
    return 'L’autorisation bancaire a expiré. Reconnectez la banque pour actualiser vos données.'
  }
  if (error.status === 409 && error.message === 'BANK_SYNC_IN_PROGRESS') {
    return 'Une synchronisation est déjà en cours.'
  }
  if (error.status === 409 && error.message === CONNECTION_REQUIRED) {
    return 'Reconnectez votre banque pour lancer une nouvelle synchronisation.'
  }
  return 'Les données financières n’ont pas pu être actualisées.'
}

async function optionalConnection() {
  try {
    return await getBankConnection()
  } catch (error) {
    if (error instanceof ApiError && error.status === 409 && error.message === CONNECTION_REQUIRED) {
      return null
    }
    throw error
  }
}

async function optionalSynchronizationStatus() {
  try {
    return await getSynchronizationStatus()
  } catch (error) {
    if (error instanceof ApiError && error.status === 409 && error.message === CONNECTION_REQUIRED) {
      return null
    }
    throw error
  }
}

export function FinancialDataProvider({ children }) {
  const [accounts, setAccounts] = useState([])
  const [connection, setConnection] = useState(undefined)
  const [dashboardSummary, setDashboardSummary] = useState({
    month: currentMonth(),
    balanceTotals: [],
    monthlyCashFlows: [],
  })
  const [recentTransactions, setRecentTransactions] = useState([])
  const [synchronizationStatus, setSynchronizationStatus] = useState(null)
  const [loading, setLoading] = useState(true)
  const [synchronizing, setSynchronizing] = useState(false)
  const [error, setError] = useState('')
  const [synchronizationError, setSynchronizationError] = useState('')
  const [dataVersion, setDataVersion] = useState(0)

  const reload = useCallback(async ({ quiet = false } = {}) => {
    if (!quiet) setLoading(true)
    setError('')
    const month = currentMonth()
    const results = await Promise.allSettled([
      optionalConnection(),
      listLocalAccounts(),
      listLocalTransactions({ page: 0, size: 6 }),
      optionalSynchronizationStatus(),
      getDashboardSummary(month),
    ])
    const [connectionResult, accountsResult, transactionsResult, statusResult, summaryResult] = results

    if (connectionResult.status === 'fulfilled') setConnection(connectionResult.value)
    if (accountsResult.status === 'fulfilled') setAccounts(accountsResult.value || [])
    if (transactionsResult.status === 'fulfilled') setRecentTransactions(transactionsResult.value?.items || [])
    if (statusResult.status === 'fulfilled') setSynchronizationStatus(statusResult.value)
    if (summaryResult.status === 'fulfilled') setDashboardSummary(summaryResult.value)

    const failure = results.find((result) => result.status === 'rejected')
    if (failure) setError(controlledFinancialError(failure.reason))
    if (!failure) setDataVersion((value) => value + 1)
    setLoading(false)
    return !failure
  }, [])

  useEffect(() => {
    reload()
  }, [reload])

  const synchronize = useCallback(async () => {
    if (synchronizing || synchronizationStatus?.status === 'RUNNING') return false
    setSynchronizing(true)
    setSynchronizationError('')
    try {
      await startSynchronization()
      await reload({ quiet: true })
      return true
    } catch (requestError) {
      setSynchronizationError(controlledFinancialError(requestError))
      await reload({ quiet: true })
      return false
    } finally {
      setSynchronizing(false)
    }
  }, [reload, synchronizationStatus?.status, synchronizing])

  const connected = connection?.status === 'ACTIVE' && !connection.reconnectRequired
  const historical = !connected && accounts.length > 0
  const synchronizationInProgress = synchronizing || synchronizationStatus?.status === 'RUNNING'
  const value = useMemo(() => ({
    accounts,
    connected,
    connection,
    dashboardSummary,
    dataVersion,
    error,
    historical,
    loading,
    recentTransactions,
    reload,
    synchronizationError,
    synchronizationStatus,
    synchronize,
    synchronizing: synchronizationInProgress,
  }), [
    accounts,
    connected,
    connection,
    dashboardSummary,
    dataVersion,
    error,
    historical,
    loading,
    recentTransactions,
    reload,
    synchronizationError,
    synchronizationStatus,
    synchronize,
    synchronizationInProgress,
  ])

  return <FinancialDataContext.Provider value={value}>{children}</FinancialDataContext.Provider>
}

export function useFinancialData() {
  const context = useContext(FinancialDataContext)
  if (!context) throw new Error('useFinancialData must be used within FinancialDataProvider')
  return context
}
