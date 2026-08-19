import { apiRequest } from './apiClient'

export function getProfile() {
  return apiRequest('/api/profile')
}
