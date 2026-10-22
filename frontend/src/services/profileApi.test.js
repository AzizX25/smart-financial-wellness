import { beforeEach, describe, expect, it, vi } from 'vitest'
import { apiRequest } from './apiClient'
import { getProfile, updateProfile } from './profileApi'

vi.mock('./apiClient', () => ({ apiRequest: vi.fn() }))

describe('contrat du profil via le client authentifié partagé', () => {
  beforeEach(() => { vi.clearAllMocks() })

  it('charge le profil sans désactiver l’authentification', async () => {
    const response = { displayName: 'Profil serveur' }
    apiRequest.mockResolvedValue(response)
    expect(await getProfile()).toBe(response)
    expect(apiRequest).toHaveBeenCalledWith('/api/profile')
  })

  it('envoie exactement les champs éditables du PUT et aucun champ d’identité ou d’état', async () => {
    const editable = {
      firstName: 'Noura', lastName: 'Ben Salah', displayName: 'Noura B.',
      interfacePreferences: {
        languageTag: 'fr-TN', timeZone: 'Africa/Tunis', theme: 'SYSTEM',
        textSize: 'MEDIUM', highContrast: false, reducedMotion: true,
      },
      financialPreferences: {
        primaryCurrency: 'TND', monthStartDay: 1, lowBalanceThreshold: 75.1234, hideHomeAmounts: false,
      },
    }
    const server = { ...editable, displayName: 'Nom serveur' }
    apiRequest.mockResolvedValue(server)
    const response = await updateProfile({
      ...editable,
      id: 'must-not-send', email: 'must-not-send@example.test', accountStatus: 'ACTIVE',
      identityStatus: 'ACTIVE', emailVerified: true, verifiedAt: 'must-not-send', notificationPreferences: [],
      interfacePreferences: { ...editable.interfacePreferences, userId: 'must-not-send' },
      financialPreferences: { ...editable.financialPreferences, id: 'must-not-send' },
    })
    expect(response).toBe(server)
    expect(apiRequest).toHaveBeenCalledWith('/api/profile', { method: 'PUT', body: editable })
  })
})
