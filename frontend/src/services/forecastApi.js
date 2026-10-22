import { apiRequest } from './apiClient'

export function generateCurrentForecast(currency, timezone) {
  const query = new URLSearchParams({ currency, timezone })
  return apiRequest(`/api/forecasts/current?${query}`, { method: 'POST' })
}

export function listForecasts(currency, page = 0, size = 12) {
  const query = new URLSearchParams({ currency, page: String(page), size: String(size) })
  return apiRequest(`/api/forecasts?${query}`)
}

export function getForecast(forecastId) {
  return apiRequest(`/api/forecasts/${encodeURIComponent(forecastId)}`)
}

export function simulateForecast(forecastId, adjustments) {
  return apiRequest(`/api/forecasts/${encodeURIComponent(forecastId)}/simulations`, { method: 'POST', body: adjustments })
}
