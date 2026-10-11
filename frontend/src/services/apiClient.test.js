import { beforeEach, describe, expect, it, vi } from 'vitest'
import { connectMockBank } from './bankApi'
import { confirmCsvImport, previewCsvImport } from './csvImportApi'
import {
  assignTransactionCategory,
  createCategory,
  deleteCategory,
  forgetMerchantMemory,
  getCategorySummary,
  listCategorizationReviews,
  listCategories,
  listMerchantMemories,
  runCategorization,
  updateCategory,
} from './categoryApi'
import {
  getDashboardSummary,
  getSynchronizationStatus,
  listLocalAccounts,
  listLocalTransactions,
  startSynchronization,
} from './financialApi'
import {
  apiRequest,
  clearAccessToken,
  setAccessToken,
  setSessionInvalidHandler,
} from './apiClient'

function jsonResponse(payload, status = 200) {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

describe('client API principal', () => {
  beforeEach(() => {
    clearAccessToken()
    setSessionInvalidHandler(() => {})
    vi.restoreAllMocks()
  })

  it('envoie le multipart CSV avec le bearer et renouvelle la session sans altérer le fichier', async () => {
    setAccessToken('old-token')
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce(jsonResponse({}, 401))
      .mockResolvedValueOnce(jsonResponse({ accessToken: 'new-token' }))
      .mockResolvedValueOnce(jsonResponse({ jobId: 'IMPORT/1' }))
      .mockResolvedValueOnce(jsonResponse({ status: 'COMPLETED' })))
    const file = new File(['date;amount\n2026-10-01;-2'], 'statement.csv', { type: 'text/csv' })
    await previewCsvImport(file, { accountId: 'ACCOUNT-1' })
    const firstBody = fetch.mock.calls[0][1].body
    expect(firstBody).toBeInstanceOf(FormData)
    expect(firstBody.get('file').name).toBe('statement.csv')
    expect(firstBody.get('request').type).toBe('application/json')
    expect(fetch.mock.calls[0][1].headers.has('Content-Type')).toBe(false)
    expect(fetch.mock.calls[2][1].body).toBe(firstBody)
    expect(fetch.mock.calls[2][1].headers.get('Authorization')).toBe('Bearer new-token')
    await confirmCsvImport('IMPORT/1', file, { accountId: 'ACCOUNT-1' })
    expect(fetch.mock.calls[3][0]).toBe('http://localhost:8080/api/imports/csv/IMPORT%2F1/confirm')
  })

  it('utilise le backend principal et attache le bearer sans contacter le port 8081', async () => {
    setAccessToken('main-access-token')
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({ ok: true })))

    await connectMockBank({ username: 'client.demo', password: 'secret-value' })

    const [url, options] = fetch.mock.calls[0]
    expect(url).toBe('http://localhost:8080/api/bank-connections/mock-bank')
    expect(url).not.toContain(':8081')
    expect(options.headers.get('Authorization')).toBe('Bearer main-access-token')
  })

  it('invalide la session uniquement si le renouvellement confirme un 401', async () => {
    const invalidated = vi.fn()
    setSessionInvalidHandler(invalidated)
    setAccessToken('expired-access-token')
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce(jsonResponse({ message: 'Unauthorized' }, 401))
      .mockResolvedValueOnce(jsonResponse({ message: 'Refresh token is invalid' }, 401)))

    await expect(apiRequest('/api/auth/me')).rejects.toMatchObject({ status: 401 })
    expect(invalidated).toHaveBeenCalledOnce()
    expect(fetch).toHaveBeenCalledTimes(2)
  })

  it('conserve la session lors du rejet des identifiants mock-bank', async () => {
    const invalidated = vi.fn()
    setSessionInvalidHandler(invalidated)
    setAccessToken('valid-main-token')
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({
      message: 'Invalid mock-bank credentials',
      path: '/api/bank-connections/mock-bank',
    }, 422)))

    await expect(connectMockBank({ username: 'wrong', password: 'wrong' }))
      .rejects.toMatchObject({ status: 422 })
    expect(invalidated).not.toHaveBeenCalled()
    expect(fetch).toHaveBeenCalledOnce()
  })

  it('adresse toutes les données financières au backend principal sans port 8081', async () => {
    setAccessToken('main-access-token')
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({ items: [] })))
    await startSynchronization()
    await getSynchronizationStatus()
    await listLocalAccounts()
    await listLocalTransactions({ accountId: 'ACCOUNT-1', direction: 'DEBIT', search: 'loyer' })
    await getDashboardSummary('2026-08')
    expect(fetch).toHaveBeenCalledTimes(5)
    fetch.mock.calls.forEach(([url]) => {
      expect(url).toMatch(/^http:\/\/localhost:8080\/api\//)
      expect(url).not.toContain(':8081')
    })
  })

  it('ne déconnecte pas l’utilisateur lors d’un échec de synchronisation fournisseur', async () => {
    const invalidated = vi.fn()
    setSessionInvalidHandler(invalidated)
    setAccessToken('valid-main-token')
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({ message: 'Mock-bank service is unavailable' }, 503)))
    await expect(startSynchronization()).rejects.toMatchObject({ status: 503 })
    expect(invalidated).not.toHaveBeenCalled()
  })

  it('adresse toute la catégorisation au backend principal sans port 8081', async () => {
    setAccessToken('main-access-token')
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({})))

    await listCategories()
    await createCategory({ name: 'Vacances', nature: 'EXPENSE' })
    await updateCategory('CATEGORY-1', { name: 'Voyages', nature: 'EXPENSE' })
    await deleteCategory('CATEGORY-1')
    await runCategorization()
    await getCategorySummary('2026-08')
    await assignTransactionCategory('TRANSACTION-1', 'CATEGORY-2')
    await listCategorizationReviews(1, 20)
    await listMerchantMemories()
    await forgetMerchantMemory('MEMORY/1')

    expect(fetch).toHaveBeenCalledTimes(10)
    expect(fetch.mock.calls[7][0]).toContain('/api/categorization-reviews?page=1&size=20')
    expect(fetch.mock.calls[9][0]).toContain('/api/merchant-memories/MEMORY%2F1')
    fetch.mock.calls.forEach(([url]) => {
      expect(url).toMatch(/^http:\/\/localhost:8080\/api\//)
      expect(url).not.toContain(':8081')
    })
  })
})
