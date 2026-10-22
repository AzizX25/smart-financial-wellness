import { beforeEach, describe, expect, it, vi } from 'vitest'
import { apiRequest } from './adminApi'
import { ADMIN_TOKEN_KEY } from './authSession'

describe('apiRequest', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    sessionStorage.clear()
  })

  it('attache le jeton administrateur aux appels API', async () => {
    sessionStorage.setItem(ADMIN_TOKEN_KEY, 'admin-token')
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{"ok":true}', {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    })))

    await apiRequest('/api/admin/dashboard')

    const [, options] = fetch.mock.calls[0]
    expect(options.headers.get('Authorization')).toBe('Bearer admin-token')
  })

  it('efface uniquement la session après une réponse 401', async () => {
    sessionStorage.setItem(ADMIN_TOKEN_KEY, 'admin-token')
    const unauthorized = vi.fn()
    window.addEventListener('mock-bank:unauthorized', unauthorized)
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{"message":"Session expirée"}', {
      status: 401,
      headers: { 'Content-Type': 'application/json' },
    })))

    await expect(apiRequest('/api/admin/dashboard')).rejects.toMatchObject({ status: 401 })
    expect(sessionStorage.getItem(ADMIN_TOKEN_KEY)).toBeNull()
    expect(unauthorized).toHaveBeenCalledOnce()
    window.removeEventListener('mock-bank:unauthorized', unauthorized)
  })

  it('conserve la session pour une erreur métier contrôlée', async () => {
    sessionStorage.setItem(ADMIN_TOKEN_KEY, 'admin-token')
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{"message":"Conflit"}', {
      status: 409,
      headers: { 'Content-Type': 'application/json' },
    })))

    await expect(apiRequest('/api/admin/customers')).rejects.toMatchObject({ status: 409 })
    expect(sessionStorage.getItem(ADMIN_TOKEN_KEY)).toBe('admin-token')
  })
})
