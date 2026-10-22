import { apiRequest } from './apiClient'

export const createManualTransaction = (request) => apiRequest('/api/transactions', {
  method: 'POST', body: request,
})

export const updateManualTransaction = (id, request) => apiRequest(`/api/transactions/${encodeURIComponent(id)}`, {
  method: 'PUT', body: request,
})
