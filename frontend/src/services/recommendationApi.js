import { apiRequest } from './apiClient'

export function generateRecommendations(currency) {
  return apiRequest(`/api/recommendations/current?${new URLSearchParams({ currency })}`, { method: 'POST' })
}

export function listRecommendations(currency, page = 0, size = 12) {
  return apiRequest(`/api/recommendations?${new URLSearchParams({ currency, page, size })}`)
}

export function setRecommendationStatus(id, status) {
  return apiRequest(`/api/recommendations/${encodeURIComponent(id)}/status`, {
    method: 'PATCH', body: { status },
  })
}

export function explainRecommendation(id) {
  return apiRequest(`/api/recommendations/${encodeURIComponent(id)}/explanation`, { method: 'POST' })
}
