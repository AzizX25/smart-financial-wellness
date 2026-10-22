import { apiRequest } from './apiClient'

export function startSynchronization() {
  return apiRequest('/api/bank/synchronizations', { method: 'POST' })
}

export function getSynchronizationStatus() {
  return apiRequest('/api/bank/synchronizations/status')
}

export function listLocalAccounts() {
  return apiRequest('/api/accounts')
}

export function getDashboardSummary(month) {
  const query = new URLSearchParams({ month })
  return apiRequest(`/api/dashboard/summary?${query}`)
}

export function listLocalTransactions({
  accountId,
  direction,
  categoryId,
  uncategorized,
  approved,
  from,
  page = 0,
  search,
  size = 20,
  to,
} = {}) {
  const query = new URLSearchParams({ page: String(page), size: String(size) })
  if (accountId) query.set('accountId', accountId)
  if (direction) query.set('direction', direction)
  if (categoryId) query.set('categoryId', categoryId)
  if (uncategorized) query.set('uncategorized', 'true')
  if (typeof approved === 'boolean') query.set('approved', String(approved))
  if (from) query.set('from', from)
  if (to) query.set('to', to)
  if (search?.trim()) query.set('search', search.trim())
  return apiRequest(`/api/transactions?${query}`)
}
