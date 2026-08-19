import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useAuth } from '../auth/AuthContext'
import { useFinancialData } from '../financial/FinancialDataContext'
import DashboardPage from './DashboardPage'

vi.mock('../auth/AuthContext', () => ({ useAuth: vi.fn() }))
vi.mock('../financial/FinancialDataContext', () => ({ useFinancialData: vi.fn() }))

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
    useAuth.mockReturnValue({ user: { firstName: 'Noura', displayName: 'Noura Ben Salah' } })
    useFinancialData.mockReturnValue(state())
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
