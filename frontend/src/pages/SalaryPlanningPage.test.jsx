import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../services/apiClient'
import {
  getSalaryPlan,
  getSalaryPlanProposals,
  listSalaryPlans,
  saveSalaryPlan,
} from '../services/salaryPlanningApi'
import SalaryPlanningPage from './SalaryPlanningPage'

vi.mock('../services/salaryPlanningApi', () => ({
  getSalaryPlan: vi.fn(),
  getSalaryPlanProposals: vi.fn(),
  listSalaryPlans: vi.fn(),
  saveSalaryPlan: vi.fn(),
}))

const today = new Date()
const DEFAULT_MONTH = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}`

function shiftMonth(value, offset) {
  const [year, month] = value.split('-').map(Number)
  const shifted = new Date(year, month - 1 + offset, 1)
  return `${shifted.getFullYear()}-${String(shifted.getMonth() + 1).padStart(2, '0')}`
}

const FOOD = '11111111-1111-1111-1111-111111111111'
const HOUSING = '22222222-2222-2222-2222-222222222222'
const metadata = {
  metadataAvailable: true,
  periodStart: '2026-05-01',
  periodEndInclusive: '2026-07-31',
  usableMonthCount: 2,
  method: 'HISTORICAL_MONTHLY_AVERAGE',
  methodVersion: '1',
  fallbackReason: null,
}

function plan(overrides = {}) {
  return {
    month: DEFAULT_MONTH,
    currency: 'TND',
    incomes: [{ label: 'Salaire', expectedAmount: 2500 }],
    allocations: [
      { categoryId: FOOD, categoryName: 'Alimentation', recommendedAmount: 100, confirmedAmount: 120, effectiveAmount: 120 },
      { categoryId: HOUSING, categoryName: 'Logement', recommendedAmount: 700, confirmedAmount: null, effectiveAmount: 700 },
    ],
    totalIncome: 2500,
    totalExpenses: 820,
    savingCapacity: 1680,
    deficit: 0,
    recommendationMetadata: metadata,
    createdAt: '2026-08-01T00:00:00Z',
    updatedAt: '2026-08-01T00:00:00Z',
    version: 0,
    ...overrides,
  }
}

function proposal(overrides = {}) {
  return {
    month: DEFAULT_MONTH,
    currency: 'TND',
    allocations: [
      { categoryId: FOOD, categoryName: 'Alimentation', recommendedAmount: 0 },
      { categoryId: HOUSING, categoryName: 'Logement', recommendedAmount: 0 },
    ],
    totalRecommendedExpenses: 0,
    recommendationMetadata: {
      ...metadata,
      usableMonthCount: 0,
      fallbackReason: 'NO_USABLE_HISTORY',
    },
    ...overrides,
  }
}

function page(items = []) {
  return { items, page: 0, size: 12, totalElements: items.length, totalPages: items.length ? 1 : 0 }
}

describe('Salary Planning connecté', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    getSalaryPlan.mockResolvedValue(plan())
    getSalaryPlanProposals.mockResolvedValue(proposal())
    listSalaryPlans.mockResolvedValue(page())
    saveSalaryPlan.mockResolvedValue(plan())
  })

  it('charge le plan existant sans demander de propositions', async () => {
    render(<SalaryPlanningPage />)

    expect(await screen.findByDisplayValue('Salaire')).toBeInTheDocument()
    expect(screen.getByText('Une recommandation déterministe et vérifiable')).toBeInTheDocument()
    expect(screen.getByText(/1[\s\u202f]680,000 TND/)).toBeInTheDocument()
    expect(getSalaryPlan).toHaveBeenCalledWith(DEFAULT_MONTH, 'TND')
    expect(getSalaryPlanProposals).not.toHaveBeenCalled()
  })

  it('charge les propositions uniquement après un 404 et affiche le repli sans historique', async () => {
    getSalaryPlan.mockRejectedValueOnce(new ApiError(404, 'Salary plan not found'))
    render(<SalaryPlanningPage />)

    expect(await screen.findByText(/Ce plan n’est pas encore enregistré/)).toBeInTheDocument()
    expect(screen.getByText(/pas encore assez d’historique/)).toBeInTheDocument()
    expect(getSalaryPlanProposals).toHaveBeenCalledWith(DEFAULT_MONTH, 'TND')
  })

  it('ne transforme pas les autres erreurs API en absence de plan', async () => {
    getSalaryPlan.mockRejectedValueOnce(new ApiError(503, 'Unavailable'))
    render(<SalaryPlanningPage />)

    expect(await screen.findByText('Le plan n’a pas pu être chargé pour le moment.')).toBeInTheDocument()
    expect(getSalaryPlanProposals).not.toHaveBeenCalled()
  })

  it('édite les revenus et overrides, retire un override et remplace le brouillon par la réponse sauvegardée', async () => {
    const saved = plan({
      incomes: [
        { label: 'Salaire net', expectedAmount: 2800 },
        { label: 'Prime', expectedAmount: 400 },
      ],
      allocations: [
        { categoryId: FOOD, categoryName: 'Alimentation', recommendedAmount: 100, confirmedAmount: null, effectiveAmount: 100 },
        { categoryId: HOUSING, categoryName: 'Logement', recommendedAmount: 700, confirmedAmount: 750, effectiveAmount: 750 },
      ],
      totalIncome: 3200,
      totalExpenses: 850,
      savingCapacity: 2350,
    })
    saveSalaryPlan.mockResolvedValueOnce(saved)
    render(<SalaryPlanningPage />)

    await screen.findByDisplayValue('Salaire')
    fireEvent.click(screen.getByRole('button', { name: 'Utiliser la recommandation' }))
    fireEvent.change(screen.getByLabelText('Libellé du revenu 1'), { target: { value: '  Salaire net  ' } })
    fireEvent.change(screen.getByLabelText('Montant du revenu 1 (TND)'), { target: { value: '2800.0000' } })
    fireEvent.click(screen.getByRole('button', { name: 'Ajouter un revenu' }))
    fireEvent.change(screen.getByLabelText('Libellé du revenu 2'), { target: { value: 'Prime' } })
    fireEvent.change(screen.getByLabelText('Montant du revenu 2 (TND)'), { target: { value: '400' } })
    fireEvent.change(screen.getByLabelText('Montant confirmé pour Logement (TND)'), { target: { value: '750.0000' } })
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer le plan complet' }))

    await waitFor(() => expect(saveSalaryPlan).toHaveBeenCalledWith(DEFAULT_MONTH, 'TND', {
      incomes: [
        { label: 'Salaire net', expectedAmount: 2800 },
        { label: 'Prime', expectedAmount: 400 },
      ],
      allocationOverrides: [{ categoryId: HOUSING, confirmedAmount: 750 }],
    }))
    expect(await screen.findByText('Votre plan mensuel a bien été enregistré.')).toBeInTheDocument()
    expect(screen.getByDisplayValue('Prime')).toBeInTheDocument()
    expect(screen.getByLabelText('Montant confirmé pour Alimentation (TND)')).toHaveValue('')
    expect(screen.getByLabelText('Montant confirmé pour Logement (TND)')).toHaveValue('750')
  })

  it('affiche les validations françaises avant tout enregistrement', async () => {
    getSalaryPlan.mockRejectedValueOnce(new ApiError(404, 'Not found'))
    render(<SalaryPlanningPage />)

    await screen.findByText(/Ce plan n’est pas encore enregistré/)
    fireEvent.click(screen.getByRole('button', { name: 'Ajouter ma première source' }))
    fireEvent.change(screen.getByLabelText('Montant du revenu 1 (TND)'), { target: { value: '-1' } })
    fireEvent.click(screen.getByRole('button', { name: 'Ajouter un revenu' }))
    fireEvent.change(screen.getByLabelText('Libellé du revenu 2'), { target: { value: 'Prime' } })
    fireEvent.change(screen.getByLabelText('Montant du revenu 2 (TND)'), { target: { value: '1.00001' } })
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer le plan complet' }))

    expect(await screen.findByText('Donnez un nom à cette source de revenu.')).toBeInTheDocument()
    expect(screen.getByText('Le montant doit être positif ou nul.')).toBeInTheDocument()
    expect(screen.getByText('Utilisez au maximum quatre chiffres après la virgule.')).toBeInTheDocument()
    expect(saveSalaryPlan).not.toHaveBeenCalled()
  })

  it('affiche le déficit et signale les métadonnées indisponibles d’un ancien plan', async () => {
    getSalaryPlan.mockResolvedValueOnce(plan({
      totalIncome: 500,
      totalExpenses: 650,
      savingCapacity: -150,
      deficit: 150,
      recommendationMetadata: {
        metadataAvailable: false,
        periodStart: null,
        periodEndInclusive: null,
        usableMonthCount: null,
        method: null,
        methodVersion: null,
        fallbackReason: null,
      },
    }))
    render(<SalaryPlanningPage />)

    expect(await screen.findByText(/Les détails de cette recommandation ne sont pas disponibles/)).toBeInTheDocument()
    expect(screen.getByText(/Votre plan présente un déficit de/)).toBeInTheDocument()
    expect(screen.getAllByText(/150,000 TND/).length).toBeGreaterThan(0)
  })

  it('recharge par mois et devise et permet d’ouvrir un plan précédent', async () => {
    const previousMonth = shiftMonth(DEFAULT_MONTH, -1)
    const olderPlan = plan({ month: previousMonth })
    getSalaryPlan.mockImplementation((selectedMonth, selectedCurrency) => Promise.resolve(plan({
      month: selectedMonth,
      currency: selectedCurrency,
    })))
    listSalaryPlans.mockResolvedValue(page([olderPlan]))
    render(<SalaryPlanningPage />)

    const previousButton = await screen.findByRole('button', { name: /capacité d’épargne/ })
    fireEvent.click(previousButton)
    await waitFor(() => expect(getSalaryPlan).toHaveBeenCalledWith(previousMonth, 'TND'))

    fireEvent.change(screen.getByLabelText('Mois'), { target: { value: DEFAULT_MONTH } })
    await waitFor(() => expect(getSalaryPlan).toHaveBeenCalledWith(DEFAULT_MONTH, 'TND'))
    fireEvent.change(screen.getByLabelText('Devise'), { target: { value: 'USD' } })
    await waitFor(() => expect(getSalaryPlan).toHaveBeenCalledWith(DEFAULT_MONTH, 'USD'))
    expect(listSalaryPlans).toHaveBeenCalledWith('USD', 0, 12)
  })
})
