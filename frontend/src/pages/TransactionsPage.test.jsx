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
  page: 0, size: 5, totalElements: 22, totalPages: 5, first: true, last: false,
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
    expect(screen.getByText(/\+.*100[,.]0 TND/)).toHaveClass('amount-credit')
    expect(screen.getByText(/−.*25[,.]0 TND/)).toHaveClass('amount-debit')
    expect(listLocalTransactions).toHaveBeenCalledWith(expect.objectContaining({ page: 0, size: 5 }))
  })

  it('envoie les filtres catégorie et non catégorisée au backend', async () => {
    render(<TransactionsPage />)
    await screen.findByRole('heading', { name: 'Crédit réel' })
    fireEvent.click(screen.getByText('Affiner la recherche'))
    fireEvent.change(screen.getByLabelText('Catégorie'), { target: { value: 'CAT-FOOD' } })
    await waitFor(() => expect(listLocalTransactions).toHaveBeenLastCalledWith(expect.objectContaining({ categoryId: 'CAT-FOOD', uncategorized: false })))
    fireEvent.change(screen.getByLabelText('Catégorie'), { target: { value: 'UNCATEGORIZED' } })
    await waitFor(() => expect(listLocalTransactions).toHaveBeenLastCalledWith(expect.objectContaining({ categoryId: '', uncategorized: true })))
  })

  it('applique et retire un choix manuel sans recharger la page', async () => {
    const financialState = state()
    useFinancialData.mockReturnValue(financialState)
    let savedPage = page
    listLocalTransactions.mockImplementation(async () => savedPage)
    assignTransactionCategory.mockImplementation(async (id, categoryId) => {
      const response = { transactionId: id, categoryId: categoryId || null, categoryName: categoryId ? 'Alimentation et courses' : null,
        categoryNature: categoryId ? 'EXPENSE' : null, assignmentSource: categoryId ? 'MANUAL' : 'UNCATEGORIZED' }
      savedPage = { ...page, items: page.items.map(item => item.id === id ? { ...item, ...response, categoryAssignmentSource: response.assignmentSource, categoryApproved: Boolean(categoryId) } : item) }
      return response
    })
    render(<TransactionsPage />)
    await screen.findByRole('heading', { name: 'Débit réel' })
    fireEvent.click(screen.getByRole('button', { name: 'Toutes les transactions', exact: true }))
    await screen.findByRole('heading', { name: 'Débit réel' })
    const select = screen.getByLabelText('Catégorie de Débit réel')
    fireEvent.change(select, { target: { value: 'CAT-FOOD' } })
    expect(assignTransactionCategory).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: 'Approuver la catégorie de Débit réel' }))
    await waitFor(() => expect(select).toHaveValue('CAT-FOOD'))
    expect(financialState.reload).toHaveBeenCalledWith({ quiet: true })
    expect(screen.getByText('Choix manuel')).toBeInTheDocument()
    fireEvent.change(select, { target: { value: '' } })
    fireEvent.click(screen.getByRole('button', { name: 'Retirer la catégorie de Débit réel' }))
    await waitFor(() => expect(select).toHaveValue(''))
  })

  it('conserve le choix visible si une affectation échoue', async () => {
    assignTransactionCategory.mockRejectedValue(new Error('failure'))
    render(<TransactionsPage />)
    await screen.findByRole('heading', { name: 'Crédit réel' })
    fireEvent.change(screen.getByLabelText('Catégorie de Crédit réel'), { target: { value: 'CAT-TRANSFER' } })
    fireEvent.click(screen.getByRole('button', { name: 'Approuver la catégorie de Crédit réel' }))
    expect(await screen.findByText(/n’a pas pu être modifiée/)).toBeInTheDocument()
    expect(screen.getByLabelText('Catégorie de Crédit réel')).toHaveValue('CAT-TRANSFER')
    expect(screen.getByText('Choix non enregistré : confirmez avec le bouton.')).toBeInTheDocument()
  })

  it('charge la page suivante', async () => {
    listLocalTransactions.mockResolvedValueOnce(page).mockResolvedValue({ ...page, page: 1, first: false, last: true })
    render(<TransactionsPage />)
    await screen.findByRole('heading', { name: 'Crédit réel' })
    fireEvent.click(screen.getByRole('button', { name: 'Suivante' }))
    await waitFor(() => expect(listLocalTransactions).toHaveBeenLastCalledWith(expect.objectContaining({ page: 1, size: 5 })))
  })

  it('shows five server-paginated operations and replaces them with the next five', async () => {
    const items = Array.from({ length: 5 }, (_, index) => ({
      ...page.items[1], id: `TX-${index}`, description: `Opération ${index + 1}`,
    }))
    const nextItems = items.map((item, index) => ({ ...item, id: `NEXT-${index}`, description: `Opération ${index + 6}` }))
    listLocalTransactions.mockResolvedValueOnce({ ...page, items })
      .mockResolvedValue({ ...page, items: nextItems, page: 1, first: false })
    render(<TransactionsPage />)
    await screen.findByRole('heading', { name: 'Opération 5' })
    expect(document.querySelectorAll('#transactions-history .transaction-row')).toHaveLength(5)
    fireEvent.click(screen.getByRole('button', { name: 'Suivante' }))
    await screen.findByRole('heading', { name: 'Opération 10' })
    expect(screen.queryByRole('heading', { name: 'Opération 1' })).not.toBeInTheDocument()
    expect(document.querySelectorAll('#transactions-history .transaction-row')).toHaveLength(5)
    expect(listLocalTransactions).toHaveBeenLastCalledWith(expect.objectContaining({ page: 1, size: 5 }))
  })

  it('affiche un état vide contrôlé', async () => {
    listLocalTransactions.mockResolvedValue({ ...page, items: [], totalElements: 0, totalPages: 0, last: true })
    render(<TransactionsPage />)
    expect(await screen.findByRole('heading', { name: 'Aucune transaction' })).toBeInTheDocument()
  })

  it('keeps import and receipt tools behind explicit buttons without replacing history data', async () => {
    render(<TransactionsPage />)
    await screen.findByRole('heading', { name: 'Crédit réel' })
    expect(document.getElementById('transactions-history')).toBeVisible()
    expect(document.getElementById('transactions-import')).not.toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: 'Importer un CSV' }))
    expect(document.getElementById('transactions-import')).toBeVisible()
    expect(document.querySelector('#transactions-import details')).toHaveAttribute('open')
    expect(document.getElementById('transactions-history')).not.toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: 'Lire un ticket' }))
    expect(document.querySelector('#transactions-receipt details')).toHaveAttribute('open')
    fireEvent.click(screen.getByRole('button', { name: 'Historique' }))
    expect(screen.getByRole('heading', { name: 'Crédit réel' })).toBeVisible()
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
