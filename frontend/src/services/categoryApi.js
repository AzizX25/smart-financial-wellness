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

export function getCategorySummary(month) {
  return apiRequest(`/api/categories/summary?${new URLSearchParams({ month })}`)
}

export const assignTransactionCategory = (transactionId, categoryId) => apiRequest(
  `/api/transactions/${transactionId}/category`,
  { method: 'PATCH', body: { categoryId: categoryId || null } },
)
