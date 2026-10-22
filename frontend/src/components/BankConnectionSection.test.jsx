import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useFinancialData } from '../financial/FinancialDataContext'
import { connectMockBank, disconnectMockBank } from '../services/bankApi'
import { ApiError } from '../services/apiClient'
import BankConnectionSection from './BankConnectionSection'

vi.mock('../financial/FinancialDataContext', () => ({ useFinancialData: vi.fn() }))
vi.mock('../services/bankApi', () => ({
  connectMockBank: vi.fn(),
  disconnectMockBank: vi.fn(),
}))

const activeConnection = { status: 'ACTIVE', reconnectRequired: false }
const account = {
  id: '10000000-0000-0000-0000-000000000010',
  displayName: 'Compte intégration',
  accountType: 'CHECKING',
  currency: 'TND',
  maskedAccountReference: '•••• 4321',
  currentBalance: 125,
  availableBalance: 120,
  localStatus: 'ACTIVE',
}

function financialState(overrides = {}) {
  return {
    accounts: [],
    connected: false,
    connection: null,
    error: '',
    historical: false,
    loading: false,
    recentTransactions: [],
    reload: vi.fn().mockResolvedValue(true),
    synchronizationError: '',
    synchronizationStatus: null,
    synchronize: vi.fn().mockResolvedValue(true),
    synchronizing: false,
    ...overrides,
  }
}

describe('parcours de connexion bancaire', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    connectMockBank.mockResolvedValue(activeConnection)
    disconnectMockBank.mockResolvedValue(null)
    useFinancialData.mockReturnValue(financialState())
  })

  it('connecte, efface le mot de passe et lance automatiquement la synchronisation', async () => {
    const state = financialState()
    useFinancialData.mockReturnValue(state)
    render(<BankConnectionSection />)

    fireEvent.change(screen.getByLabelText('Identifiant mock-bank'), { target: { value: 'client.integration' } })
    const password = screen.getByLabelText('Mot de passe mock-bank')
    fireEvent.change(password, { target: { value: 'bank-password' } })
    fireEvent.click(screen.getByRole('button', { name: 'Connecter ma banque' }))

    await waitFor(() => expect(connectMockBank).toHaveBeenCalledWith({ username: 'client.integration', password: 'bank-password' }))
    expect(password).toHaveValue('')
    await waitFor(() => expect(state.synchronize).toHaveBeenCalledOnce())
    expect(await screen.findByText(/connectée et les données locales sont synchronisées/i)).toBeInTheDocument()
  })

  it('conserve la connexion et affiche une erreur contrôlée si la synchronisation échoue', async () => {
    const state = financialState({ synchronize: vi.fn().mockResolvedValue(false), synchronizationError: 'La banque simulée est indisponible.' })
    useFinancialData.mockReturnValue(state)
    render(<BankConnectionSection />)

    fireEvent.change(screen.getByLabelText('Identifiant mock-bank'), { target: { value: 'client.integration' } })
    fireEvent.change(screen.getByLabelText('Mot de passe mock-bank'), { target: { value: 'bank-password' } })
    fireEvent.click(screen.getByRole('button', { name: 'Connecter ma banque' }))

    expect(await screen.findByText(/connectée, mais la synchronisation doit être relancée/i)).toBeInTheDocument()
    expect(disconnectMockBank).not.toHaveBeenCalled()
  })

  it('affiche un rejet de connexion contrôlé sans conserver le mot de passe', async () => {
    connectMockBank.mockRejectedValue(new ApiError(422, 'Invalid mock-bank credentials'))
    render(<BankConnectionSection />)
    fireEvent.change(screen.getByLabelText('Identifiant mock-bank'), { target: { value: 'inactive' } })
    const password = screen.getByLabelText('Mot de passe mock-bank')
    fireEvent.change(password, { target: { value: 'incorrect' } })
    fireEvent.click(screen.getByRole('button', { name: 'Connecter ma banque' }))
    expect(await screen.findByText(/Identifiants de banque simulée incorrects/i)).toBeInTheDocument()
    expect(password).toHaveValue('')
  })

  it('rend les données locales et Actualiser déclenche la synchronisation persistante', async () => {
    const state = financialState({
      accounts: [account],
      connected: true,
      connection: activeConnection,
      recentTransactions: [{ id: 'TX-1', accountId: account.id, bookingDate: '2026-08-03', amount: 25, direction: 'CREDIT', currency: 'TND', description: 'Crédit ajouté' }],
      synchronizationStatus: { status: 'SUCCEEDED', lastSuccessfulAt: '2026-08-03T08:00:00Z' },
    })
    useFinancialData.mockReturnValue(state)
    render(<BankConnectionSection />)

    expect(screen.getByRole('heading', { name: 'Compte intégration' })).toBeInTheDocument()
    expect(screen.getByText('Crédit ajouté')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /Actualiser/ }))
    await waitFor(() => expect(state.synchronize).toHaveBeenCalledOnce())
  })

  it('déconnecte sans masquer les données historiques locales', async () => {
    const state = financialState({ accounts: [account], connected: true, connection: activeConnection })
    useFinancialData.mockReturnValue(state)
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    render(<BankConnectionSection />)

    fireEvent.click(screen.getByRole('button', { name: 'Déconnecter la banque' }))
    await waitFor(() => expect(disconnectMockBank).toHaveBeenCalledOnce())
    expect(state.reload).toHaveBeenCalled()
    expect(screen.getByRole('heading', { name: 'Compte intégration' })).toBeInTheDocument()
    expect(await screen.findByText(/historique synchronisé reste disponible/i)).toBeInTheDocument()
  })
})
