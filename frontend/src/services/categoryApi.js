import { apiRequest } from './apiClient'

export const listCategories = () => apiRequest('/api/categories')

export const createCategory = (category) => apiRequest('/api/categories', {
  method: 'POST',
  body: category,
})

export const updateCategory = (categoryId, category) => apiRequest(`/api/categories/${categoryId}`, {
  method: 'PATCH',
  body: category,
})

export const deleteCategory = (categoryId) => apiRequest(`/api/categories/${categoryId}`, { method: 'DELETE' })

export const runCategorization = () => apiRequest('/api/categorization-runs', { method: 'POST' })

export const runMerchantRecognition = () => apiRequest('/api/categorization-runs/merchant-recognition', { method: 'POST' })

export const listMerchantSuggestions = () => apiRequest('/api/merchant-suggestions')

export function listCategorizationReviews(page = 0, size = 20) {
  return apiRequest(`/api/categorization-reviews?${new URLSearchParams({ page, size })}`)
}

export const listMerchantMemories = () => apiRequest('/api/merchant-memories')

export const forgetMerchantMemory = (memoryId) => apiRequest(
  `/api/merchant-memories/${encodeURIComponent(memoryId)}`,
  { method: 'DELETE' },
)

export const acceptMerchantSuggestion = (transactionId, predictionId) => apiRequest(
  `/api/transactions/${encodeURIComponent(transactionId)}/merchant-suggestions/${encodeURIComponent(predictionId)}/accept`,
  { method: 'POST' },
)

export const rejectMerchantSuggestion = (transactionId, predictionId) => apiRequest(
  `/api/transactions/${encodeURIComponent(transactionId)}/merchant-suggestions/${encodeURIComponent(predictionId)}/reject`,
  { method: 'POST' },
)

export function getCategorySummary(month) {
  return apiRequest(`/api/categories/summary?${new URLSearchParams({ month })}`)
}

export const assignTransactionCategory = (transactionId, categoryId) => apiRequest(
  `/api/transactions/${transactionId}/category`,
  { method: 'PATCH', body: { categoryId: categoryId || null } },
)
