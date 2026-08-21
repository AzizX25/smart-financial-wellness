import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useFinancialData } from '../financial/FinancialDataContext'
import {
  assignTransactionCategory,
  listCategorizationReviews,
  listCategories,
  listMerchantMemories,
  listMerchantSuggestions,
} from '../services/categoryApi'
import { listLocalTransactions } from '../services/financialApi'
import TransactionsPage from './TransactionsPage'

vi.mock('../financial/FinancialDataContext', () => ({ useFinancialData: vi.fn() }))
vi.mock('../services/financialApi', () => ({ listLocalTransactions: vi.fn() }))
vi.mock('../services/categoryApi', () => ({
  acceptMerchantSuggestion: vi.fn(),
  assignTransactionCategory: vi.fn(),
  forgetMerchantMemory: vi.fn(),
  listCategorizationReviews: vi.fn(),
  listCategories: vi.fn(),
  listMerchantMemories: vi.fn(),
  listMerchantSuggestions: vi.fn(),
  rejectMerchantSuggestion: vi.fn(),
  runMerchantRecognition: vi.fn(),
}))

const account = { id: 'ACCOUNT-1', displayName: 'Compte réel' }
const categories = [
  { id: 'CAT-FOOD', name: 'Alimentation et courses', nature: 'EXPENSE', system: true },
  { id: 'CAT-SALARY', name: 'Salaire', nature: 'INCOME', system: true },
  { id: 'CAT-TRANSFER', name: 'Transferts', nature: 'TRANSFER', system: false },
]
const page = {
  items: [
    { id: 'TX-C', accountId: account.id, bookingDate: '2026-08-04', description: 'Crédit réel', counterpartyName: 'Employeur', direction: 'CREDIT', amount: 100, currency: 'TND', categoryId: 'CAT-SALARY', categoryName: 'Salaire', categoryAssignmentSource: 'DETERMINISTIC_RULE' },
    { id: 'TX-D', accountId: account.id, bookingDate: '2026-08-03', description: 'Débit réel', direction: 'DEBIT', amount: 25, currency: 'TND', categoryId: null, categoryName: null, categoryAssignmentSource: 'UNCATEGORIZED' },
  ],
  page: 0, size: 20, totalElements: 22, totalPages: 2, first: true, last: false,
}
const emptyReviewPage = {
  items: [], page: 0, size: 20, totalElements: 0, totalPages: 0, first: true, last: true,
}

function state(overrides = {}) {
  return {
    accounts: [account],
    connected: true,
    dataVersion: 1,
    historical: false,
    reload: vi.fn().mockResolvedValue(true),
    synchronizationError: '',
    synchronize: vi.fn(),
    synchronizing: false,
    ...overrides,
  }
}

describe('transactions locales catégorisées', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    useFinancialData.mockReturnValue(state())
    listCategories.mockResolvedValue(categories)
    listMerchantSuggestions.mockResolvedValue([])
    listCategorizationReviews.mockResolvedValue({ ...emptyReviewPage })
    listMerchantMemories.mockResolvedValue([])
    listLocalTransactions.mockResolvedValue(page)
  })

  it('affiche crédits, débits, catégories et pagination', async () => {
    render(<TransactionsPage />)
    expect(await screen.findByRole('heading', { name: 'Crédit réel' })).toBeInTheDocument()
    expect(screen.getByLabelText('Catégorie de Crédit réel')).toHaveValue('CAT-SALARY')
    expect(screen.getByLabelText('Catégorie de Débit réel')).toHaveValue('')
    expect(screen.getByText('22 transactions')).toBeInTheDocument()
    expect(screen.getByText(/\+.*100[,.]000/)).toHaveClass('amount-credit')
    expect(screen.getByText(/−.*25[,.]000/)).toHaveClass('amount-debit')
  })

  it('envoie les filtres catégorie et non catégorisée au backend', async () => {
    render(<TransactionsPage />)
    await screen.findByRole('heading', { name: 'Crédit réel' })
    fireEvent.change(screen.getByLabelText('Catégorie'), { target: { value: 'CAT-FOOD' } })
    await waitFor(() => expect(listLocalTransactions).toHaveBeenLastCalledWith(expect.objectContaining({ categoryId: 'CAT-FOOD', uncategorized: false })))
    fireEvent.change(screen.getByLabelText('Catégorie'), { target: { value: 'UNCATEGORIZED' } })
    await waitFor(() => expect(listLocalTransactions).toHaveBeenLastCalledWith(expect.objectContaining({ categoryId: '', uncategorized: true })))
  })

  it('applique et retire un choix manuel sans recharger la page', async () => {
    const financialState = state()
    useFinancialData.mockReturnValue(financialState)
    assignTransactionCategory.mockResolvedValueOnce({ transactionId: 'TX-D', categoryId: 'CAT-FOOD', categoryName: 'Alimentation et courses', categoryNature: 'EXPENSE', assignmentSource: 'MANUAL' })
      .mockResolvedValueOnce({ transactionId: 'TX-D', categoryId: null, categoryName: null, categoryNature: null, assignmentSource: 'UNCATEGORIZED' })
    render(<TransactionsPage />)
    await screen.findByRole('heading', { name: 'Débit réel' })
    const select = screen.getByLabelText('Catégorie de Débit réel')
    fireEvent.change(select, { target: { value: 'CAT-FOOD' } })
    await waitFor(() => expect(select).toHaveValue('CAT-FOOD'))
    expect(financialState.reload).toHaveBeenCalledWith({ quiet: true })
    expect(screen.getByText('Choix manuel')).toBeInTheDocument()
    fireEvent.change(select, { target: { value: '' } })
    await waitFor(() => expect(select).toHaveValue(''))
  })

  it('conserve le choix visible si une affectation échoue', async () => {
    assignTransactionCategory.mockRejectedValue(new Error('failure'))
    render(<TransactionsPage />)
    await screen.findByRole('heading', { name: 'Crédit réel' })
    fireEvent.change(screen.getByLabelText('Catégorie de Crédit réel'), { target: { value: 'CAT-TRANSFER' } })
    expect(await screen.findByText(/n’a pas pu être modifiée/)).toBeInTheDocument()
    expect(screen.getByLabelText('Catégorie de Crédit réel')).toHaveValue('CAT-SALARY')
  })

  it('charge la page suivante', async () => {
    listLocalTransactions.mockResolvedValueOnce(page).mockResolvedValue({ ...page, page: 1, first: false, last: true })
    render(<TransactionsPage />)
    await screen.findByRole('heading', { name: 'Crédit réel' })
    fireEvent.click(screen.getByRole('button', { name: 'Suivante' }))
    await waitFor(() => expect(listLocalTransactions).toHaveBeenLastCalledWith(expect.objectContaining({ page: 1 })))
  })

  it('affiche un état vide contrôlé', async () => {
    listLocalTransactions.mockResolvedValue({ ...page, items: [], totalElements: 0, totalPages: 0, last: true })
    render(<TransactionsPage />)
    expect(await screen.findByRole('heading', { name: 'Aucune transaction' })).toBeInTheDocument()
  })

  it('conserve les données catégorisées après déconnexion et erreur', async () => {
    useFinancialData.mockReturnValue(state({ connected: false, historical: true, synchronizationError: 'Échec fournisseur contrôlé.' }))
    listLocalTransactions.mockResolvedValueOnce(page).mockRejectedValueOnce(new Error('provider'))
    const { rerender } = render(<TransactionsPage />)
    expect(await screen.findByRole('heading', { name: 'Crédit réel' })).toBeInTheDocument()
    useFinancialData.mockReturnValue(state({ connected: false, historical: true, dataVersion: 2, synchronizationError: 'Échec fournisseur contrôlé.' }))
    rerender(<TransactionsPage />)
    expect(await screen.findByText(/page précédente reste affichée/i)).toBeInTheDocument()
    expect(screen.getByLabelText('Catégorie de Crédit réel')).toHaveValue('CAT-SALARY')
  })
})
