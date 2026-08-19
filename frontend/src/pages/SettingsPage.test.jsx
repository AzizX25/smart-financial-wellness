import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { getProfile } from '../services/profileApi'
import SettingsPage from './SettingsPage'

vi.mock('../auth/AuthContext', () => ({
  useAuth: () => ({
    user: {
      displayName: 'Valeur de session',
      email: 'session@example.test',
    },
  }),
}))

vi.mock('../services/profileApi', () => ({ getProfile: vi.fn() }))
vi.mock('../components/BankConnectionSection', () => ({
  default: () => <div>Section bancaire test</div>,
}))

const backendProfile = {
  id: '10000000-0000-0000-0000-000000000099',
  email: 'noura.profile@example.test',
  firstName: 'Noura',
  lastName: 'Ben Salah',
  displayName: 'Noura Profile',
  accountStatus: 'ACTIVE',
  identityStatus: 'ACTIVE',
  emailVerified: true,
  interfacePreferences: {
    languageTag: 'fr-TN',
    timeZone: 'Africa/Tunis',
    theme: 'SYSTEM',
    textSize: 'MEDIUM',
    highContrast: false,
    reducedMotion: true,
  },
  financialPreferences: {
    primaryCurrency: 'TND',
    monthStartDay: 1,
    lowBalanceThreshold: 75,
    hideHomeAmounts: false,
  },
  notificationPreferences: [],
}

describe('profil authentifié', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    getProfile.mockResolvedValue(backendProfile)
  })

  it('remplace les données fictives par le profil renvoyé par le backend', async () => {
    render(<SettingsPage />)

    expect(screen.getByText('Chargement du profil…')).toBeInTheDocument()
    expect(await screen.findByRole('heading', { name: 'Noura Profile' })).toBeInTheDocument()
    expect(screen.getByText('noura.profile@example.test')).toBeInTheDocument()
    expect(screen.getByText('Africa/Tunis')).toBeInTheDocument()
    expect(screen.getByText('Jour 1')).toBeInTheDocument()
    expect(screen.queryByText('Amel Trabelsi')).not.toBeInTheDocument()
  })
})
