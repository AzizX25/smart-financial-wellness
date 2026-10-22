import { beforeEach, expect, it, vi } from 'vitest'
import { apiRequest } from './apiClient'
import * as manualTransactionApi from './manualTransactionApi'
vi.mock('./apiClient', () => ({ apiRequest: vi.fn() }))
beforeEach(() => vi.clearAllMocks())
it('uses the authenticated shared client and encodes IDs and the revision', async () => {
  const request = { amount: '12.3456', description: 'Personnel' }
  await manualTransactionApi.createManualTransaction(request)
  expect(apiRequest).toHaveBeenCalledWith('/api/transactions', { method: 'POST', body: request })
  await manualTransactionApi.updateManualTransaction('test/id', request)
  expect(apiRequest).toHaveBeenCalledWith('/api/transactions/test%2Fid', { method: 'PUT', body: request })
  expect(manualTransactionApi).not.toHaveProperty('deleteManualTransaction')
  expect(apiRequest).toHaveBeenCalledTimes(2)
})
