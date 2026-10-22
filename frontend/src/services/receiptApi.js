import { apiRequest } from './apiClient'

export function extractReceipt(file) {
  const body = new FormData()
  body.append('file', file)
  return apiRequest('/api/receipts/extract', { method: 'POST', body })
}
