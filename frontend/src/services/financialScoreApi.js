import { apiRequest } from './apiClient'

export function calculateCurrentFinancialScore(currency) {
  const query = new URLSearchParams({ currency })
  return apiRequest(`/api/financial-scores/current?${query}`, { method: 'POST' })
}

export function listFinancialScores(currency, page = 0, size = 12) {
  const query = new URLSearchParams({ currency, page: String(page), size: String(size) })
  return apiRequest(`/api/financial-scores?${query}`)
}

export function getFinancialScore(scoreId) {
  return apiRequest(`/api/financial-scores/${encodeURIComponent(scoreId)}`)
}
