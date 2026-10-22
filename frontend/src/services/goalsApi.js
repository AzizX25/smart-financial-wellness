import { apiRequest } from './apiClient'

function currencyQuery(currency, extra = {}) {
  return new URLSearchParams({ currency, ...extra })
}

export function listGoals(currency, includeArchived = false) {
  const query = currencyQuery(currency, { includeArchived: String(includeArchived) })
  return apiRequest(`/api/goals?${query}`)
}

export function createGoal(goal) {
  return apiRequest('/api/goals', { method: 'POST', body: goal })
}

export function getGoal(goalId) {
  return apiRequest(`/api/goals/${encodeURIComponent(goalId)}`)
}

export function updateGoal(goalId, goal) {
  return apiRequest(`/api/goals/${encodeURIComponent(goalId)}`, { method: 'PUT', body: goal })
}

export function changeGoalStatus(goalId, status) {
  return apiRequest(`/api/goals/${encodeURIComponent(goalId)}/status`, {
    method: 'PATCH',
    body: { status },
  })
}

export function archiveGoal(goalId) {
  return apiRequest(`/api/goals/${encodeURIComponent(goalId)}`, { method: 'DELETE' })
}

export function listGoalContributions(goalId) {
  return apiRequest(`/api/goals/${encodeURIComponent(goalId)}/contributions`)
}

export function addGoalContribution(goalId, contribution) {
  return apiRequest(`/api/goals/${encodeURIComponent(goalId)}/contributions`, {
    method: 'POST',
    body: contribution,
  })
}

export function deleteGoalContribution(goalId, contributionId) {
  return apiRequest(
    `/api/goals/${encodeURIComponent(goalId)}/contributions/${encodeURIComponent(contributionId)}`,
    { method: 'DELETE' },
  )
}

export function getGoalAllocations(month, currency) {
  const query = currencyQuery(currency)
  return apiRequest(`/api/goals/allocations/${encodeURIComponent(month)}?${query}`)
}

export function replaceGoalAllocations(month, currency, allocations) {
  const query = currencyQuery(currency)
  return apiRequest(`/api/goals/allocations/${encodeURIComponent(month)}?${query}`, {
    method: 'PUT',
    body: { allocations },
  })
}

export function simulateGoal(goalId, monthlyContribution) {
  return apiRequest(`/api/goals/${encodeURIComponent(goalId)}/simulations`, {
    method: 'POST',
    body: { monthlyContribution },
  })
}
