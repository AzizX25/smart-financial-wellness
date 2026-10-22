import { beforeEach, expect, it, vi } from 'vitest'
import { apiRequest } from './apiClient'
import { listLocalTransactions } from './financialApi'

vi.mock('./apiClient', () => ({ apiRequest: vi.fn() }))
beforeEach(() => vi.clearAllMocks())

it('encodes both approval states with the authenticated client without dropping false', async () => {
  for (const approved of [false, true]) {
    await listLocalTransactions({ approved, accountId: 'safe/id', search: 'synthetic & merchant', page: 2, size: 5 })
    const params = new URLSearchParams(apiRequest.mock.lastCall[0].split('?')[1])
    expect(params.get('approved')).toBe(String(approved))
    expect(params.get('accountId')).toBe('safe/id')
    expect(params.get('search')).toBe('synthetic & merchant')
    expect(params.get('page')).toBe('2')
    expect(params.get('size')).toBe('5')
  }
})

it('preserves the unfiltered endpoint for existing callers and the complete history', async () => {
  for (const request of [{}, { approved: null }]) {
    await listLocalTransactions(request)
    expect(new URLSearchParams(apiRequest.mock.lastCall[0].split('?')[1]).has('approved')).toBe(false)
  }
})
