import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useAuth } from '../auth/AuthContext'
import { useFinancialData } from '../financial/FinancialDataContext'
import { calculateCurrentFinancialScore } from '../services/financialScoreApi'
import DashboardPage from './DashboardPage'

vi.mock('../auth/AuthContext', () => ({ useAuth: vi.fn() }))
vi.mock('../financial/FinancialDataContext', () => ({ useFinancialData: vi.fn() }))
vi.mock('../services/financialScoreApi', () => ({ calculateCurrentFinancialScore: vi.fn() }))

const score = {
  available: true,
  status: 'AVAILABLE',
  id: 'SCORE-1',
  currency: 'TND',
  asOfDate: '2026-08-23',
  timeZone: 'Africa/Tunis',
  historyStart: '2026-02-01',
  historyEnd: '2026-07-31',
  calculationVersion: 'financial-wellness-score-v1',
  score: 76.25,
  bandCode: 'EN_PROGRESSION',
  bandLabel: 'En progression',
  bandDescription: "L'indicateur synthétique se situe entre 60 et 80.",
  availableBaseWeight: 65,
  factors: [
    { code: 'SAVINGS_RATE', baseWeight: 30, rawValue: 0.12, unit: 'RATIO', score: 60, available: true, explanation: "Le taux d'épargne est calculé sur six mois.", unavailabilityReason: null },
    { code: 'ESSENTIAL_EXPENSE_BURDEN', baseWeight: 20, rawValue: null, unit: 'RATIO', score: null, available: false, explanation: 'Les catégories actuelles ne distinguent pas les dépenses essentielles.', unavailabilityReason: 'ESSENTIAL_CLASSIFICATION_UNAVAILABLE' },
  ],
  createdAt: '2026-08-23T10:15:30Z',
  disclaimer: 'Cet indicateur est une estimation pédagogique de votre situation financière. Il ne constitue ni un score de crédit ni un conseil financier.',
}

const account = {
  id: 'ACCOUNT-1',
  displayName: 'Compte courant réel',
  accountType: 'CHECKING',
  maskedAccountReference: '•••• C001',
  currency: 'TND',
  currentBalance: 3000,
  lastSynchronizedAt: '2026-08-04T09:00:00Z',
}

function state(overrides = {}) {
  return {
    accounts: [account],
    connected: true,
    dashboardSummary: {
      month: '2026-08',
      balanceTotals: [{ currency: 'TND', totalBalance: 3000, accountCount: 1 }],
      monthlyCashFlows: [{ currency: 'TND', income: 1200, expenses: 450, netCashFlow: 750 }],
    },
    dataVersion: 1,
    error: '',
    historical: false,
    loading: false,
    recentTransactions: [{ id: 'TX-1', accountId: account.id, bookingDate: '2026-08-03', description: 'Salaire réel', direction: 'CREDIT', amount: 1200, currency: 'TND' }],
    synchronizationError: '',
    synchronizationStatus: { status: 'SUCCEEDED', lastSuccessfulAt: '2026-08-04T09:00:00Z' },
    synchronize: vi.fn(),
    synchronizing: false,
    ...overrides,
  }
}

describe('tableau de bord financier dynamique', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    calculateCurrentFinancialScore.mockResolvedValue(score)
    useAuth.mockReturnValue({ user: { firstName: 'Noura', displayName: 'Noura Ben Salah' } })
    useFinancialData.mockReturnValue(state())
  })

  it('affiche le score serveur, ses facteurs, sa couverture et le libellé accessible', async () => {
    render(<MemoryRouter><DashboardPage /></MemoryRouter>)

    expect(await screen.findByRole('img', { name: /score de bien-être financier 76.25 sur 100/i })).toBeInTheDocument()
    expect(calculateCurrentFinancialScore).toHaveBeenCalledWith('TND')
    expect(screen.getByText('Poids des dépenses essentielles')).toBeInTheDocument()
    expect(screen.getByText(/Couverture : 65 %/)).toBeInTheDocument()
    expect(screen.getByText(/ni un score de crédit ni un conseil financier/)).toBeInTheDocument()
    expect(screen.queryByText(/recommandation|confiance/i)).not.toBeInTheDocument()
  })

  it('distingue les données insuffisantes d’une erreur API', async () => {
    calculateCurrentFinancialScore.mockResolvedValue({
      ...score,
      available: false,
      status: 'INSUFFICIENT_DATA',
      id: null,
      score: null,
      bandCode: null,
      bandLabel: null,
      bandDescription: null,
      availableBaseWeight: 45,
    })
    const { unmount } = render(<MemoryRouter><DashboardPage /></MemoryRouter>)
    expect(await screen.findByRole('heading', { name: 'Score non disponible' })).toBeInTheDocument()
    expect(screen.queryByText(/n'a pas pu être calculé/)).not.toBeInTheDocument()
    unmount()

    calculateCurrentFinancialScore.mockRejectedValue(new Error('network'))
    render(<MemoryRouter><DashboardPage /></MemoryRouter>)
    expect(await screen.findByText(/n'a pas pu être calculé/)).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Score non disponible' })).not.toBeInTheDocument()
  })

  it('isole les devises, ignore une réponse obsolète et recharge après invalidation financière', async () => {
    let resolveEuro
    const euroRequest = new Promise((resolve) => { resolveEuro = resolve })
    calculateCurrentFinancialScore.mockImplementation((currency) => currency === 'EUR'
      ? euroRequest
      : Promise.resolve({ ...score, currency: 'TND', score: 81, bandCode: 'SOLIDE', bandLabel: 'Solide' }))
    let currentState = state({
      dashboardSummary: {
        month: '2026-08',
        balanceTotals: [
          { currency: 'EUR', totalBalance: 200, accountCount: 1 },
          { currency: 'TND', totalBalance: 3000, accountCount: 1 },
        ],
        monthlyCashFlows: [
          { currency: 'EUR', income: 20, expenses: 5, netCashFlow: 15 },
          { currency: 'TND', income: 1200, expenses: 450, netCashFlow: 750 },
        ],
      },
    })
    useFinancialData.mockImplementation(() => currentState)
    const view = render(<MemoryRouter><DashboardPage /></MemoryRouter>)
    await waitFor(() => expect(calculateCurrentFinancialScore).toHaveBeenCalledWith('EUR'))
    fireEvent.change(screen.getByLabelText('Devise analysée'), { target: { value: 'TND' } })
    expect(await screen.findByRole('heading', { name: 'Solide' })).toBeInTheDocument()
    resolveEuro({ ...score, currency: 'EUR', score: 25, bandCode: 'A_RENFORCER', bandLabel: 'À renforcer' })
    await Promise.resolve()
    expect(screen.queryByRole('heading', { name: 'À renforcer' })).not.toBeInTheDocument()

    currentState = { ...currentState, dataVersion: 2 }
    view.rerender(<MemoryRouter><DashboardPage /></MemoryRouter>)
    await waitFor(() => expect(calculateCurrentFinancialScore.mock.calls
      .filter(([currency]) => currency === 'TND')).toHaveLength(2))
  })

  it('affiche l’utilisateur, le compte réel, les agrégats mensuels et les transactions récentes', () => {
    render(<MemoryRouter><DashboardPage /></MemoryRouter>)
    expect(screen.getByRole('heading', { name: 'Bonjour Noura' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Compte courant réel' })).toBeInTheDocument()
    expect(screen.getByText('Salaire réel')).toBeInTheDocument()
    expect(screen.getByText('Dernier succès')).toBeInTheDocument()
    expect(screen.getAllByText(/1[\s\u202f]?200[,.]000/).length).toBeGreaterThan(0)
    expect(screen.getByText(/450[,.]000/)).toBeInTheDocument()
    expect(screen.getByText(/750[,.]000/)).toBeInTheDocument()
    expect(screen.queryByText(/score encourageant/i)).not.toBeInTheDocument()
    expect(screen.queryByText(/Conseils personnalisés/i)).not.toBeInTheDocument()
    expect(screen.queryByText(/Prévision de fin de mois/i)).not.toBeInTheDocument()
  })

  it('affiche un total unique lorsque tous les soldes partagent une devise', () => {
    render(<MemoryRouter><DashboardPage /></MemoryRouter>)
    expect(screen.getByText('Solde total')).toBeInTheDocument()
    expect(screen.getAllByText(/3[\s\u202f]?000[,.]000/).length).toBeGreaterThan(0)
    expect(screen.queryByRole('heading', { name: 'Résumé par devise' })).not.toBeInTheDocument()
  })

  it('sépare les devises sans fabriquer de conversion ou de total global', () => {
    useFinancialData.mockReturnValue(state({
      dashboardSummary: {
        month: '2026-08',
        balanceTotals: [
          { currency: 'EUR', totalBalance: 200, accountCount: 1 },
          { currency: 'TND', totalBalance: 3000, accountCount: 1 },
        ],
        monthlyCashFlows: [
          { currency: 'EUR', income: 20, expenses: 5, netCashFlow: 15 },
          { currency: 'TND', income: 1200, expenses: 450, netCashFlow: 750 },
        ],
      },
    }))
    render(<MemoryRouter><DashboardPage /></MemoryRouter>)
    expect(screen.getByRole('heading', { name: 'Résumé par devise' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'EUR' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'TND' })).toBeInTheDocument()
    expect(screen.queryByText('Solde total')).not.toBeInTheDocument()
  })

  it('désactive les clics répétés pendant la synchronisation', () => {
    const current = state({ synchronizing: true })
    useFinancialData.mockReturnValue(current)
    render(<MemoryRouter><DashboardPage /></MemoryRouter>)
    const button = screen.getByRole('button', { name: /Synchronisation en cours/ })
    expect(button).toBeDisabled()
    fireEvent.click(button)
    expect(current.synchronize).not.toHaveBeenCalled()
  })

  it('conserve les données en cas d’échec et signale clairement un historique déconnecté', () => {
    useFinancialData.mockReturnValue(state({
      connected: false,
      historical: true,
      synchronizationError: 'La banque simulée est momentanément indisponible.',
    }))
    render(<MemoryRouter><DashboardPage /></MemoryRouter>)
    expect(screen.getByText(/Ces données sont historiques/)).toBeInTheDocument()
    expect(screen.getByText(/dernières données disponibles restent affichées/)).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Compte courant réel' })).toBeInTheDocument()
    expect(screen.getByText('Salaire réel')).toBeInTheDocument()
  })

  it('affiche les états sans compte et sans transaction sans valeur financière fictive', () => {
    useFinancialData.mockReturnValue(state({
      accounts: [],
      connected: false,
      dashboardSummary: { month: '2026-08', balanceTotals: [], monthlyCashFlows: [] },
      recentTransactions: [],
      synchronizationStatus: null,
    }))
    render(<MemoryRouter><DashboardPage /></MemoryRouter>)
    expect(screen.getByText(/Aucune banque connectée et aucun historique/)).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Aucune transaction' })).toBeInTheDocument()
    expect(screen.queryByText('Solde global')).not.toBeInTheDocument()
  })
})
