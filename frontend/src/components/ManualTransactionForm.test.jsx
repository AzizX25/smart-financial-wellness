import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../services/apiClient'
import { createImportAccount } from '../services/csvImportApi'
import { createManualTransaction, updateManualTransaction } from '../services/manualTransactionApi'
import ManualTransactionForm from './ManualTransactionForm'
import { positiveDecimal } from '../services/manualTransactionValidation'

vi.mock('../services/csvImportApi', () => ({ createImportAccount: vi.fn() }))
vi.mock('../services/manualTransactionApi', () => ({ createManualTransaction: vi.fn(), updateManualTransaction: vi.fn() }))

const account = { id: 'personal', accountOrigin: 'MANUAL', localStatus: 'ACTIVE', displayName: 'Personnel', currency: 'TND' }
const bank = { id: 'bank', accountOrigin: 'BANK_CONNECTION', localStatus: 'ACTIVE', displayName: 'Banque', currency: 'TND' }
const categories = [{ id: 'food', name: 'Alimentation', nature: 'EXPENSE' }, { id: 'salary', name: 'Salaire', nature: 'INCOME' }]
const props = () => ({ accounts: [account, bank], categories, onSaved: vi.fn(), onCancel: vi.fn() })
function fill() {
  fireEvent.change(screen.getByLabelText("Date de l'opération"), { target: { value: '2026-10-01' } })
  fireEvent.change(screen.getByLabelText('Libellé'), { target: { value: 'Achat personnel' } })
  fireEvent.change(screen.getByLabelText('Montant positif (TND)'), { target: { value: '12,3456' } })
}

describe('manual transaction form', () => {
  beforeEach(() => vi.clearAllMocks())

  it('edits a bank transaction as a local correction without changing its account or source', async () => {
    const state = props()
    updateManualTransaction.mockResolvedValue({ id: 'bank-row', version: 5 })
    render(<ManualTransactionForm {...state} transaction={{ id: 'bank-row', sourceType: 'BANK_SYNC', accountId: bank.id,
      version: 4, amountExact: '10.0000', direction: 'DEBIT', description: 'Existant bancaire', bookingDate: '2026-10-01' }} />)
    expect(screen.getByLabelText('Compte (inchangé)')).toBeDisabled()
    expect(screen.getByText(/Correction locale uniquement/)).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Montant positif (TND)'), { target: { value: '12,3456' } })
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer cette opération' }))
    await waitFor(() => expect(state.onSaved).toHaveBeenCalledWith({ id: 'bank-row', version: 5 }))
    expect(updateManualTransaction).toHaveBeenCalledWith('bank-row', { version: 4, bookingDate: '2026-10-01',
      description: 'Existant bancaire', amount: '12.3456', direction: 'DEBIT', categoryId: null })
  })

  it('validates positive scale-4 decimal strings without floating-point rounding', () => {
    expect(positiveDecimal('999999999999999,1234')).toBe('999999999999999.1234')
    for (const invalid of ['0', '-1', '1.12345', '1e3', '1000000000000000']) expect(positiveDecimal(invalid)).toBeNull()
  })

  it('creates only on personal accounts with an exact amount and bounded editable fields', async () => {
    const state = props()
    createManualTransaction.mockResolvedValue({ id: 'server', version: 1, amount: '12.3456' })
    render(<ManualTransactionForm {...state} />)
    expect(screen.queryByRole('option', { name: 'Banque · TND' })).not.toBeInTheDocument()
    fill()
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer cette opération' }))
    await waitFor(() => expect(state.onSaved).toHaveBeenCalledWith({ id: 'server', version: 1, amount: '12.3456' }))
    expect(createManualTransaction).toHaveBeenCalledWith({ accountId: 'personal', clientIdempotencyKey: expect.any(String),
      description: 'Achat personnel', bookingDate: '2026-10-01', direction: 'DEBIT', amount: '12.3456', categoryId: null })
  })

  it('retries with the same key after an ambiguous failure and does not allow duplicate submission', async () => {
    const state = props()
    createManualTransaction.mockRejectedValueOnce(new ApiError(0, 'unavailable')).mockResolvedValueOnce({ id: 'saved' })
    render(<ManualTransactionForm {...state} />)
    fill()
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer cette opération' }))
    await screen.findByText(/la même clé évite les doublons/)
    const firstKey = createManualTransaction.mock.calls[0][0].clientIdempotencyKey
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer cette opération' }))
    await waitFor(() => expect(state.onSaved).toHaveBeenCalledWith({ id: 'saved' }))
    expect(createManualTransaction.mock.calls[1][0].clientIdempotencyKey).toBe(firstKey)
  })

  it('edits with the original version and exact amount, never sending account/currency/provenance', async () => {
    const state = props()
    const transaction = { id: 'manual', sourceType: 'MANUAL', accountId: account.id, bookingDate: '2026-10-01', amountExact: '999999999999999.1234',
      amount: 999999999999999.1, description: 'Existant', direction: 'DEBIT', categoryId: 'food', version: 7 }
    updateManualTransaction.mockResolvedValue({ id: 'manual', version: 8 })
    render(<ManualTransactionForm {...state} transaction={transaction} />)
    expect(screen.getByLabelText('Compte personnel')).toBeDisabled()
    expect(screen.getByLabelText('Montant positif (TND)')).toHaveValue('999999999999999.1234')
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer cette opération' }))
    await waitFor(() => expect(updateManualTransaction).toHaveBeenCalledWith('manual', { version: 7,
      amount: '999999999999999.1234', bookingDate: '2026-10-01', description: 'Existant', direction: 'DEBIT', categoryId: 'food' }))
  })

  it('clears incompatible categories on direction change and keeps validation failures visible', async () => {
    createManualTransaction.mockRejectedValue(new ApiError(409, 'Cette opération a changé.'))
    render(<ManualTransactionForm {...props()} />)
    fill()
    fireEvent.change(screen.getByLabelText("Catégorie de l'opération"), { target: { value: 'food' } })
    fireEvent.change(screen.getByLabelText('Sens'), { target: { value: 'CREDIT' } })
    expect(screen.getByLabelText("Catégorie de l'opération")).toHaveValue('')
    expect(screen.queryByRole('option', { name: 'Alimentation' })).not.toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Montant positif (TND)'), { target: { value: '0.12345' } })
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer cette opération' }))
    expect(await screen.findByText(/quatre décimales au maximum/)).toBeInTheDocument()
    expect(createManualTransaction).not.toHaveBeenCalled()
    fireEvent.change(screen.getByLabelText('Montant positif (TND)'), { target: { value: '1' } })
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer cette opération' }))
    expect(await screen.findByText('Cette opération a changé.')).toBeInTheDocument()
  })

  it('can create an owned personal account without bank access and immediately select it', async () => {
    createImportAccount.mockResolvedValue({ id: 'new', name: 'Mes opérations personnelles', currency: 'TND' })
    render(<ManualTransactionForm {...props()} accounts={[bank]} />)
    fireEvent.click(screen.getByRole('button', { name: 'Créer ce compte' }))
    await waitFor(() => expect(screen.getByLabelText('Compte personnel')).toHaveValue('new'))
    expect(createImportAccount).toHaveBeenCalledWith({ name: 'Mes opérations personnelles', currency: 'TND', currentBalance: '0.0000' })
  })
})
