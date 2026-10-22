import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useFinancialData } from '../financial/FinancialDataContext'
import { listCategories, listMerchantSuggestions, listCategorizationReviews, listMerchantMemories, assignTransactionCategory } from '../services/categoryApi'
import { listLocalTransactions } from '../services/financialApi'
import { ApiError } from '../services/apiClient'
import TransactionsPage from './TransactionsPage'

vi.mock('../financial/FinancialDataContext', () => ({ useFinancialData: vi.fn() }))
vi.mock('../services/financialApi', () => ({ listLocalTransactions: vi.fn() }))
vi.mock('../services/manualTransactionApi', () => ({ createManualTransaction: vi.fn(), updateManualTransaction: vi.fn() }))
vi.mock('../services/categoryApi', () => ({ listCategories: vi.fn(), listMerchantSuggestions: vi.fn(), listCategorizationReviews: vi.fn(), listMerchantMemories: vi.fn(),
  acceptMerchantSuggestion: vi.fn(), assignTransactionCategory: vi.fn(), forgetMerchantMemory: vi.fn(), rejectMerchantSuggestion: vi.fn(), runMerchantRecognition: vi.fn() }))
const manual = { id: 'manual', accountId: 'personal', sourceType: 'MANUAL', version: 3,
  amount: 12, amountExact: '12.0000', currency: 'TND', direction: 'DEBIT', description: 'Achat personnel', bookingDate: '2026-10-01' }
const bank = { ...manual, id: 'bank', sourceType: 'BANK_SYNC', description: 'Opération bancaire' }
const imported = { ...manual, id: 'csv', sourceType: 'FILE_IMPORT', description: 'Opération importée' }
const page = { items: [manual, bank, imported], page: 0, size: 5, totalElements: 3, totalPages: 1, first: true, last: true }
let financial

describe('manual transaction management in the history', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    financial = { accounts: [{ id: 'personal', accountOrigin: 'MANUAL', localStatus: 'ACTIVE', displayName: 'Personnel', currency: 'TND' }],
      connected: false, dataVersion: 1, reload: vi.fn().mockResolvedValue(true), synchronize: vi.fn() }
    useFinancialData.mockReturnValue(financial)
    listLocalTransactions.mockResolvedValue(page)
    listCategories.mockResolvedValue([])
    listMerchantSuggestions.mockResolvedValue([])
    listMerchantMemories.mockResolvedValue([])
    listCategorizationReviews.mockResolvedValue({ ...page, items: [], totalElements: 0 })
  })

  it('shows editing and category approval but no deletion for any source', async () => {
    render(<TransactionsPage />)
    await screen.findByRole('heading', { name: 'Achat personnel' })
    expect(screen.getByRole('button', { name: 'Modifier Opération bancaire' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Supprimer/ })).not.toBeInTheDocument()
    for (const item of page.items) expect(screen.getByRole('button', { name: `Approuver la catégorie de ${item.description}` })).toBeEnabled()
    fireEvent.click(screen.getByRole('button', { name: 'Modifier Achat personnel' }))
    expect(screen.getByRole('region', { name: 'Modifier une opération manuelle' })).toBeInTheDocument()
    expect(screen.getByLabelText('Libellé')).toHaveValue('Achat personnel')
  })

  it('moves confirmations to the approved list and category removal back to pending using server pages', async () => {
    let rows = page.items.map(item => ({ ...item, categoryId: 'food', categoryApproved: false, categoryAssignmentSource: 'DETERMINISTIC_RULE' }))
    listCategories.mockResolvedValue([{ id: 'food', name: 'Alimentation', nature: 'EXPENSE' }])
    listLocalTransactions.mockImplementation(async ({ approved }) => {
      const items = rows.filter(item => approved === null || item.categoryApproved === approved)
      return { ...page, items, totalElements: items.length, totalPages: items.length ? 1 : 0 }
    })
    assignTransactionCategory.mockImplementation(async (id, categoryId) => {
      rows = rows.map(item => item.id === id ? { ...item, categoryId, categoryApproved: Boolean(categoryId), categoryAssignmentSource: categoryId ? 'MANUAL' : 'UNCATEGORIZED' } : item)
      return { transactionId: id, categoryId, assignmentSource: categoryId ? 'MANUAL' : 'UNCATEGORIZED' }
    })
    render(<TransactionsPage />)
    await screen.findByRole('heading', { name: bank.description })
    expect(listLocalTransactions).toHaveBeenLastCalledWith(expect.objectContaining({ approved: false, size: 5 }))
    fireEvent.click(screen.getByRole('button', { name: `Approuver la catégorie de ${bank.description}` }))
    await waitFor(() => expect(screen.queryByRole('heading', { name: bank.description })).not.toBeInTheDocument())
    await screen.findByText('2 transactions')
    fireEvent.click(screen.getByRole('button', { name: 'Approuvées', exact: true }))
    await screen.findByRole('heading', { name: bank.description })
    expect(screen.queryByRole('heading', { name: manual.description })).not.toBeInTheDocument()
    expect(screen.getByText('1 transaction')).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText(`Catégorie de ${bank.description}`), { target: { value: '' } })
    fireEvent.click(screen.getByRole('button', { name: `Retirer la catégorie de ${bank.description}` }))
    await screen.findByRole('heading', { name: 'Aucune transaction' })
    fireEvent.click(screen.getByRole('button', { name: 'À approuver', exact: true }))
    await screen.findByRole('heading', { name: bank.description })
    expect(screen.getByText('3 transactions')).toBeInTheDocument()
    expect(rows).toHaveLength(3)
    fireEvent.click(screen.getByRole('button', { name: 'Recharger la liste' }))
    await waitFor(() => expect(financial.reload.mock.calls.length).toBeGreaterThanOrEqual(3))
    expect(listLocalTransactions).toHaveBeenLastCalledWith(expect.objectContaining({ approved: false }))
  })

  it('ignores a slow pending response after switching to approved transactions', async () => {
    let finishPending
    listLocalTransactions.mockImplementation(({ approved }) => approved === false
      ? new Promise(resolve => { finishPending = resolve })
      : Promise.resolve({ ...page, items: [{ ...bank, categoryId: 'food', categoryApproved: true }], totalElements: 1 }))
    render(<TransactionsPage />)
    fireEvent.click(screen.getByRole('button', { name: 'Approuvées', exact: true }))
    await screen.findByRole('heading', { name: bank.description })
    await act(async () => { finishPending(page) })
    expect(screen.queryByRole('heading', { name: manual.description })).not.toBeInTheDocument()
    expect(screen.getByText('1 transaction')).toBeInTheDocument()
  })

  it('does not present a failed approved-list load as a legitimate empty list', async () => {
    render(<TransactionsPage />)
    await screen.findByRole('heading', { name: manual.description })
    listLocalTransactions.mockRejectedValueOnce(new ApiError(503, 'Unavailable'))
    fireEvent.click(screen.getByRole('button', { name: 'Approuvées', exact: true }))
    expect(await screen.findByText(/Cette liste n’a pas pu être chargée/)).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Aucune transaction' })).not.toBeInTheDocument()
  })

  it('returns to the last valid server page when approval empties the final pending page', async () => {
    let approvedLast = false
    const last = { ...bank, categoryId: 'food', categoryApproved: false }
    listLocalTransactions.mockImplementation(async ({ page: requested }) => ({ ...page,
      items: requested === 1 ? (approvedLast ? [] : [last]) : [manual], page: requested,
      totalElements: approvedLast ? 5 : 6, totalPages: approvedLast ? 1 : 2,
      first: requested === 0, last: approvedLast || requested === 1 }))
    assignTransactionCategory.mockImplementation(async () => {
      approvedLast = true
      return { transactionId: bank.id, categoryId: 'food', assignmentSource: 'MANUAL' }
    })
    render(<TransactionsPage />)
    await screen.findByRole('heading', { name: manual.description })
    fireEvent.click(screen.getByRole('button', { name: 'Suivante' }))
    await screen.findByRole('heading', { name: bank.description })
    fireEvent.click(screen.getByRole('button', { name: `Approuver la catégorie de ${bank.description}` }))
    await screen.findByRole('heading', { name: manual.description })
    expect(listLocalTransactions).toHaveBeenLastCalledWith(expect.objectContaining({ page: 0, approved: false }))
    expect(screen.queryByRole('heading', { name: bank.description })).not.toBeInTheDocument()
  })

  it('confirms an existing category through the normal API for all transaction sources', async () => {
    listCategories.mockResolvedValue([{ id: 'food', name: 'Alimentation', nature: 'EXPENSE' }])
    listLocalTransactions.mockResolvedValue({ ...page, items: page.items.map(item => ({ ...item, categoryId: 'food' })) })
    assignTransactionCategory.mockImplementation(async id => ({ transactionId: id, categoryId: 'food', assignmentSource: 'MANUAL' }))
    render(<TransactionsPage />)
    await screen.findByRole('heading', { name: 'Achat personnel' })
    fireEvent.click(screen.getByRole('button', { name: 'Toutes les transactions', exact: true }))
    await screen.findByRole('heading', { name: 'Achat personnel' })
    for (const item of page.items) {
      fireEvent.click(screen.getByRole('button', { name: `Approuver la catégorie de ${item.description}` }))
      await waitFor(() => expect(assignTransactionCategory).toHaveBeenCalledWith(item.id, 'food'))
      await waitFor(() => expect(screen.getByRole('button', { name: `Approuver la catégorie de ${item.description}` })).not.toBeDisabled())
    }
    expect(financial.reload).toHaveBeenCalledTimes(3)
  })

  it('shows pending and success feedback even when the category was already manually assigned', async () => {
    const saved = { ...manual, categoryId: 'food', categoryAssignmentSource: 'MANUAL' }
    listLocalTransactions.mockResolvedValue({ ...page, items: [saved] })
    listCategories.mockResolvedValue([{ id: 'food', name: 'Alimentation', nature: 'EXPENSE' }])
    let finish
    assignTransactionCategory.mockReturnValue(new Promise(resolve => { finish = resolve }))
    render(<TransactionsPage />)
    fireEvent.click(screen.getByRole('button', { name: 'Toutes les transactions', exact: true }))
    const row = (await screen.findByRole('heading', { name: saved.description })).closest('article')
    const approve = within(row).getByRole('button', { name: `Approuver la catégorie de ${saved.description}` })
    fireEvent.click(approve)
    expect(within(row).getByText('Approbation…')).toBeInTheDocument()
    expect(approve).toBeDisabled()
    fireEvent.click(approve)
    finish({ transactionId: saved.id, categoryId: 'food', assignmentSource: 'MANUAL' })
    expect(await within(row).findByRole('status')).toHaveTextContent('Catégorie approuvée. Votre choix est enregistré.')
    expect(assignTransactionCategory).toHaveBeenCalledTimes(1)
    expect(assignTransactionCategory).toHaveBeenCalledWith(saved.id, 'food')
  })

  it('explains an uncategorized approval and focuses its selector without sending an empty approval', async () => {
    render(<TransactionsPage />)
    const row = (await screen.findByRole('heading', { name: manual.description })).closest('article')
    fireEvent.click(within(row).getByRole('button', { name: `Approuver la catégorie de ${manual.description}` }))
    expect(within(row).getByRole('alert')).toHaveTextContent('Choisissez une catégorie dans la liste avant de l’approuver.')
    expect(within(row).getByLabelText(`Catégorie de ${manual.description}`)).toHaveFocus()
    expect(assignTransactionCategory).not.toHaveBeenCalled()
  })

  it('reports a failed approval beside the transaction without showing success or changing its category', async () => {
    listLocalTransactions.mockResolvedValue({ ...page, items: [{ ...bank, categoryId: 'food' }] })
    listCategories.mockResolvedValue([{ id: 'food', name: 'Alimentation', nature: 'EXPENSE' }])
    assignTransactionCategory.mockRejectedValue(new ApiError(503, 'Service indisponible'))
    render(<TransactionsPage />)
    const row = (await screen.findByRole('heading', { name: bank.description })).closest('article')
    fireEvent.click(within(row).getByRole('button', { name: `Approuver la catégorie de ${bank.description}` }))
    expect(await within(row).findByRole('alert')).toHaveTextContent('La catégorie n’a pas pu être approuvée. Réessayez.')
    expect(within(row).queryByRole('status')).not.toBeInTheDocument()
    expect(within(row).getByLabelText(`Catégorie de ${bank.description}`)).toHaveValue('food')
    expect(financial.reload).not.toHaveBeenCalled()
  })

  it('offers no transaction deletion in pending, approved or complete history', async () => {
    render(<TransactionsPage />)
    await screen.findByRole('heading', { name: 'Achat personnel' })
    for (const name of ['Approuvées', 'Toutes les transactions', 'À approuver']) {
      fireEvent.click(screen.getByRole('button', { name, exact: true }))
      await screen.findByRole('heading', { name: 'Achat personnel' })
      expect(screen.queryByRole('button', { name: /Supprimer|Confirmer la suppression/ })).not.toBeInTheDocument()
    }
    expect(financial.reload).not.toHaveBeenCalled()
  })
})
