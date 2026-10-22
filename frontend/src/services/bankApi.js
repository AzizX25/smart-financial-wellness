import { apiRequest } from './apiClient'

export function getBankConnection() {
  return apiRequest('/api/bank-connections/mock-bank')
}

export function connectMockBank(credentials) {
  return apiRequest('/api/bank-connections/mock-bank', {
    method: 'POST',
    body: credentials,
  })
}

export function disconnectMockBank() {
  return apiRequest('/api/bank-connections/mock-bank', { method: 'DELETE' })
}

export function listBankAccounts() {
  return apiRequest('/api/bank/accounts')
}

export function getBankBalance(accountId) {
  return apiRequest(`/api/bank/accounts/${accountId}/balance`)
}

export function listBankTransactions(accountId, { page = 0, size = 10 } = {}) {
  const query = new URLSearchParams({ page: String(page), size: String(size) })
  return apiRequest(`/api/bank/accounts/${accountId}/transactions?${query}`)
}
