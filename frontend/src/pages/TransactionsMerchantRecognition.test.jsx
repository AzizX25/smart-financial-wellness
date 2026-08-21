import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useFinancialData } from '../financial/FinancialDataContext'
import {
  acceptMerchantSuggestion,
  listCategories,
  listMerchantSuggestions,
  rejectMerchantSuggestion,
  runMerchantRecognition,
} from '../services/categoryApi'
import { listLocalTransactions } from '../services/financialApi'
import TransactionsPage from './TransactionsPage'

vi.mock('../financial/FinancialDataContext', () => ({ useFinancialData: vi.fn() }))
vi.mock('../services/financialApi', () => ({ listLocalTransactions: vi.fn() }))
vi.mock('../services/categoryApi', () => ({
  acceptMerchantSuggestion: vi.fn(),
  assignTransactionCategory: vi.fn(),
  listCategories: vi.fn(),
  listMerchantSuggestions: vi.fn(),
  rejectMerchantSuggestion: vi.fn(),
  runMerchantRecognition: vi.fn(),
}))

const transactionPage = {
  items: [{
    id: 'TX-DEBIT', accountId: 'ACCOUNT-1', bookingDate: '2026-08-19',
    description: 'Paiement marchand', direction: 'DEBIT', amount: 25, currency: 'TND',
    categoryId: null, categoryName: null, categoryAssignmentSource: 'UNCATEGORIZED',
  }],
  page: 0, size: 20, totalElements: 1, totalPages: 1, first: true, last: true,
}
const suggestion = {
  predictionId: 'PREDICTION-1', transactionId: 'TX-DEBIT', categoryId: 'CAT-FOOD',
  categoryCode: 'FOOD', categoryName: 'Alimentation et courses', canonicalMerchant: 'Carrefour',
  matchMethod: 'ML',
}

describe('suggestions marchandes des transactions', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    useFinancialData.mockReturnValue({
      accounts: [{ id: 'ACCOUNT-1', displayName: 'Compte courant' }], connected: true,
      dataVersion: 1, historical: false, synchronizationError: '', synchronize: vi.fn(), synchronizing: false,
    })
    listCategories.mockResolvedValue([{
      id: 'CAT-FOOD', code: 'FOOD', name: 'Alimentation et courses', nature: 'EXPENSE', system: true,
    }])
    listLocalTransactions.mockResolvedValue(transactionPage)
    listMerchantSuggestions.mockResolvedValue([])
  })

  it('analyse, affiche et accepte une suggestion sans afficher de score', async () => {
    listMerchantSuggestions.mockResolvedValueOnce([]).mockResolvedValue([suggestion])
    runMerchantRecognition.mockResolvedValue({ outcome: 'SUGGESTED', suggestedCount: 1 })
    acceptMerchantSuggestion.mockResolvedValue({
      transactionId: 'TX-DEBIT', categoryId: 'CAT-FOOD', categoryName: 'Alimentation et courses',
      categoryNature: 'EXPENSE', assignmentSource: 'ML',
    })
    render(<TransactionsPage />)
    await screen.findByRole('heading', { name: 'Paiement marchand' })

    fireEvent.click(screen.getByRole('button', { name: 'Analyser les transactions non catégorisées' }))
    expect(await screen.findByText('Carrefour')).toBeInTheDocument()
    expect(screen.getByText(/Suggestion intelligente/)).toBeInTheDocument()
    expect(screen.getByText(/Suggestion expérimentale fondée/)).toBeInTheDocument()
    expect(screen.queryByText(/%|confiance/i)).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Accepter' }))
    await waitFor(() => expect(acceptMerchantSuggestion).toHaveBeenCalledWith('TX-DEBIT', 'PREDICTION-1'))
    await waitFor(() => expect(screen.getByLabelText('Catégorie de Paiement marchand')).toHaveValue('CAT-FOOD'))
  })

  it('refuse une suggestion et conserve la transaction non catégorisée', async () => {
    listMerchantSuggestions.mockResolvedValueOnce([{ ...suggestion, matchMethod: 'EXACT_ALIAS' }]).mockResolvedValue([])
    rejectMerchantSuggestion.mockResolvedValue(null)
    render(<TransactionsPage />)
    await screen.findByText(/Alias connu/)

    fireEvent.click(screen.getByRole('button', { name: 'Refuser' }))
    await waitFor(() => expect(rejectMerchantSuggestion).toHaveBeenCalledWith('TX-DEBIT', 'PREDICTION-1'))
    expect(screen.getByLabelText('Catégorie de Paiement marchand')).toHaveValue('')
    expect(await screen.findByText(/reste non catégorisée/)).toBeInTheDocument()
  })

  it('affiche une indisponibilité contrôlée sans altérer les transactions', async () => {
    runMerchantRecognition.mockResolvedValue({ outcome: 'SERVICE_UNAVAILABLE', suggestedCount: 0 })
    render(<TransactionsPage />)
    await screen.findByRole('heading', { name: 'Paiement marchand' })

    fireEvent.click(screen.getByRole('button', { name: 'Analyser les transactions non catégorisées' }))
    expect(await screen.findByText(/service de suggestion est momentanément indisponible/)).toBeInTheDocument()
    expect(screen.getByLabelText('Catégorie de Paiement marchand')).toHaveValue('')
  })
})
