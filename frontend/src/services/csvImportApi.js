import { apiRequest } from './apiClient'

function upload(file, request) {
  const body = new FormData()
  body.append('file', file)
  body.append('request', new Blob([JSON.stringify(request)], { type: 'application/json' }))
  return body
}

export function createImportAccount(request) {
  return apiRequest('/api/imports/accounts', { method: 'POST', body: request })
}

export function previewCsvImport(file, request) {
  return apiRequest('/api/imports/csv/preview', { method: 'POST', body: upload(file, request) })
}

export function confirmCsvImport(jobId, file, request) {
  return apiRequest(`/api/imports/csv/${encodeURIComponent(jobId)}/confirm`, { method: 'POST', body: upload(file, request) })
}
