import { clearAdminToken, getAdminToken } from './authSession'

const API_BASE_URL = (import.meta.env.VITE_MOCK_BANK_API_URL || 'http://localhost:8081').replace(/\/$/, '')

export class ApiError extends Error {
  constructor(status, message, details) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.details = details
  }
}

export async function apiRequest(path, options = {}) {
  const token = getAdminToken()
  const headers = new Headers(options.headers)
  if (options.body !== undefined) {
    headers.set('Content-Type', 'application/json')
  }
  if (token) {
    headers.set('Authorization', `Bearer ${token}`)
  }

  const response = await fetch(`${API_BASE_URL}${path}`, { ...options, headers })
  if (response.status === 401) {
    clearAdminToken()
    window.dispatchEvent(new Event('mock-bank:unauthorized'))
  }
  if (!response.ok) {
    let details = null
    try {
      details = await response.json()
    } catch {
      // A safe generic error is used when the server did not return JSON.
    }
    throw new ApiError(response.status, details?.message || 'Une erreur est survenue.', details)
  }
  if (response.status === 204) {
    return null
  }
  return response.json()
}

function query(params) {
  const values = new URLSearchParams()
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') values.set(key, value)
  })
  const encoded = values.toString()
  return encoded ? `?${encoded}` : ''
}

export const adminApi = {
  login: (credentials) => apiRequest('/api/admin/auth/login', {
    method: 'POST',
    body: JSON.stringify(credentials),
  }),
  dashboard: () => apiRequest('/api/admin/dashboard'),
  customers: (params = {}) => apiRequest(`/api/admin/customers${query(params)}`),
  customer: (customerId) => apiRequest(`/api/admin/customers/${encodeURIComponent(customerId)}`),
  createCustomer: (customer) => apiRequest('/api/admin/customers', {
    method: 'POST', body: JSON.stringify(customer),
  }),
  updateCustomer: (customerId, customer) => apiRequest(`/api/admin/customers/${encodeURIComponent(customerId)}`, {
    method: 'PATCH', body: JSON.stringify(customer),
  }),
  resetCustomerPassword: (customerId, password) => apiRequest(
    `/api/admin/customers/${encodeURIComponent(customerId)}/reset-password`,
    { method: 'POST', body: JSON.stringify({ password }) },
  ),
  customerAction: (customerId, action) => apiRequest(
    `/api/admin/customers/${encodeURIComponent(customerId)}/${action}`,
    { method: 'POST' },
  ),
  accounts: (params = {}) => apiRequest(`/api/admin/accounts${query(params)}`),
  account: (accountId) => apiRequest(`/api/admin/accounts/${encodeURIComponent(accountId)}`),
  createAccount: (customerId, account) => apiRequest(
    `/api/admin/customers/${encodeURIComponent(customerId)}/accounts`,
    { method: 'POST', body: JSON.stringify(account) },
  ),
  updateAccount: (accountId, account) => apiRequest(`/api/admin/accounts/${encodeURIComponent(accountId)}`, {
    method: 'PATCH', body: JSON.stringify(account),
  }),
  accountAction: (accountId, action) => apiRequest(
    `/api/admin/accounts/${encodeURIComponent(accountId)}/${action}`,
    { method: 'POST' },
  ),
  transactions: (accountId, params = {}) => apiRequest(
    `/api/admin/accounts/${encodeURIComponent(accountId)}/transactions${query(params)}`,
  ),
  createTransaction: (accountId, transaction) => apiRequest(
    `/api/admin/accounts/${encodeURIComponent(accountId)}/transactions`,
    { method: 'POST', body: JSON.stringify(transaction) },
  ),
}
