import { apiRequest } from './apiClient'

function selectionQuery(month, currency) {
  return new URLSearchParams({ month, currency })
}

export function getSalaryPlan(month, currency) {
  const query = new URLSearchParams({ currency })
  return apiRequest(`/api/salary-plans/${encodeURIComponent(month)}?${query}`)
}

export function saveSalaryPlan(month, currency, plan) {
  const query = new URLSearchParams({ currency })
  return apiRequest(`/api/salary-plans/${encodeURIComponent(month)}?${query}`, {
    method: 'PUT',
    body: plan,
  })
}

export function listSalaryPlans(currency, page = 0, size = 12) {
  const query = new URLSearchParams({ currency, page: String(page), size: String(size) })
  return apiRequest(`/api/salary-plans?${query}`)
}

export function getSalaryPlanProposals(month, currency) {
  return apiRequest(`/api/salary-plans/proposals?${selectionQuery(month, currency)}`)
}
