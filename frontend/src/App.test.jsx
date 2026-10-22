import { render, screen } from '@testing-library/react'
import { BrowserRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import { clearAccessToken, setSessionInvalidHandler } from './services/apiClient'

const authenticatedUser = {
  id: '10000000-0000-0000-0000-000000000099',
  email: 'noura.integration@example.test',
  firstName: 'Noura',
  lastName: 'Ben Salah',
  displayName: 'Noura Ben Salah',
  accountStatus: 'ACTIVE',
  identityStatus: 'ACTIVE',
  emailVerified: true,
}

function jsonResponse(payload, status = 200) {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function renderApp(path) {
  window.history.replaceState({}, '', path)
  return render(<BrowserRouter><App /></BrowserRouter>)
}

describe('session et routage principal', () => {
  beforeEach(() => {
    clearAccessToken()
    setSessionInvalidHandler(() => {})
    vi.restoreAllMocks()
  })

  it('redirige une route protégée vers la connexion sans session renouvelable', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({ message: 'Unauthorized' }, 401)))
    renderApp('/tableau-de-bord')
    expect(await screen.findByRole('heading', { name: 'Connexion' })).toBeInTheDocument()
  })

  it('affiche dynamiquement l’utilisateur authentifié dans le shell et le tableau de bord', async () => {
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce(jsonResponse({ accessToken: 'restored-token', user: authenticatedUser }))
      .mockResolvedValueOnce(jsonResponse(authenticatedUser)))
    renderApp('/tableau-de-bord')

    expect(await screen.findByText('noura.integration@example.test')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Bonjour Noura' })).toBeInTheDocument()
    expect(screen.queryByText('Amel Trabelsi')).not.toBeInTheDocument()
  })
})
