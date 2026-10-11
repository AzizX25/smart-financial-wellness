import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useFinancialData } from '../financial/FinancialDataContext'
import { ApiError } from '../services/apiClient'
import { confirmCsvImport, createImportAccount, previewCsvImport } from '../services/csvImportApi'
import CsvImportPanel from './CsvImportPanel'

vi.mock('../financial/FinancialDataContext', () => ({ useFinancialData: vi.fn() }))
vi.mock('../services/csvImportApi', () => ({ confirmCsvImport: vi.fn(), createImportAccount: vi.fn(), previewCsvImport: vi.fn() }))

const mapping = { dateColumn: 'date', descriptionColumn: 'description', amountColumn: 'amount', directionColumn: null, currencyColumn: null }
const preview = {
  jobId: 'IMPORT-1', accountId: 'ACCOUNT-1', currency: 'TND', columns: ['date', 'description', 'amount'], mapping,
  delimiter: 'SEMICOLON', dateFormat: 'ISO', decimalSeparator: 'DOT', rowCount: 1, validCount: 1,
  invalidCount: 0, duplicateCount: 0, canImport: true, alreadyImported: false, errors: [],
  rows: [{ rowNumber: 2, bookingDate: '2026-10-01', description: 'Livres école', amount: '25.5000', direction: 'DEBIT', currency: 'TND' }],
}
const reload = vi.fn()

function start() {
  render(<CsvImportPanel />)
  fireEvent.click(screen.getByText('Importer un relevé CSV'))
}

function selectFile() {
  const file = new File(['date;description;amount\n2026-10-01;Livres école;-25.5'], 'statement.csv', { type: 'text/csv' })
  fireEvent.change(screen.getByLabelText('Fichier CSV'), { target: { files: [file] } })
  return file
}

describe('import CSV avec confirmation explicite', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    reload.mockResolvedValue(true)
    useFinancialData.mockReturnValue({ accounts: [{ id: 'ACCOUNT-1', displayName: 'Relevés TND', currency: 'TND', localStatus: 'ACTIVE' }], reload })
    previewCsvImport.mockResolvedValue(preview)
    confirmCsvImport.mockResolvedValue({ status: 'COMPLETED', importedCount: 1, duplicateCount: 0 })
  })

  it('prévisualise le fichier puis confirme le mapping exact avant de recharger les données financières', async () => {
    start()
    fireEvent.change(screen.getByLabelText('Compte destinataire'), { target: { value: 'ACCOUNT-1' } })
    const file = selectFile()
    fireEvent.click(screen.getByRole('button', { name: 'Prévisualiser le fichier' }))
    expect(await screen.findByText('Livres école')).toBeInTheDocument()
    const confirm = screen.getByRole('button', { name: "Confirmer l'import" })
    expect(confirm).toBeDisabled()
    fireEvent.click(screen.getByRole('checkbox'))
    fireEvent.click(confirm)
    expect(await screen.findByRole('heading', { name: 'Import terminé' })).toBeInTheDocument()
    expect(confirmCsvImport).toHaveBeenCalledWith('IMPORT-1', file, {
      accountId: 'ACCOUNT-1', mapping, delimiter: 'SEMICOLON', dateFormat: 'ISO', decimalSeparator: 'DOT',
    })
    expect(reload).toHaveBeenCalledWith({ quiet: true })
    expect(confirmCsvImport).toHaveBeenCalledOnce()
  })

  it('invalide la confirmation après un changement de format ou de fichier', async () => {
    start()
    fireEvent.change(screen.getByLabelText('Compte destinataire'), { target: { value: 'ACCOUNT-1' } })
    selectFile()
    fireEvent.click(screen.getByRole('button', { name: 'Prévisualiser le fichier' }))
    await screen.findByText('Livres école')
    fireEvent.click(screen.getByRole('checkbox'))
    fireEvent.change(screen.getByLabelText('Format des dates'), { target: { value: 'DAY_MONTH_YEAR' } })
    expect(screen.getByRole('button', { name: "Confirmer l'import" })).toBeDisabled()
    expect(screen.getByText(/paramètres ont changé/)).toBeInTheDocument()
    selectFile()
    expect(screen.queryByText('Livres école')).not.toBeInTheDocument()
    expect(confirmCsvImport).not.toHaveBeenCalled()
  })

  it('permet de créer un compte et déclarer son solde sans connexion bancaire', async () => {
    useFinancialData.mockReturnValue({ accounts: [], reload })
    createImportAccount.mockResolvedValue({ id: 'ACCOUNT-1', name: 'Compte de mes relevés', currency: 'TND', currentBalance: '4200.0000', reused: false })
    start()
    fireEvent.change(screen.getByLabelText('Compte destinataire'), { target: { value: 'new' } })
    fireEvent.change(screen.getByLabelText('Solde actuel déclaré'), { target: { value: '4200,0000' } })
    fireEvent.click(screen.getByRole('button', { name: 'Créer le compte' }))
    await waitFor(() => expect(screen.getByLabelText('Compte destinataire')).toHaveValue('ACCOUNT-1'))
    expect(createImportAccount).toHaveBeenCalledWith({ name: 'Compte de mes relevés', currency: 'TND', currentBalance: '4200.0000' })
    expect(screen.getByText(/Compte créé avec le solde/)).toBeInTheDocument()
  })

  it('affiche les erreurs de lignes et les répétitions sans permettre une confirmation', async () => {
    previewCsvImport.mockResolvedValue({ ...preview, validCount: 0, invalidCount: 1, canImport: false,
      errors: [{ rowNumber: 2, message: 'La devise ne correspond pas au compte.' }], rows: [] })
    start()
    fireEvent.change(screen.getByLabelText('Compte destinataire'), { target: { value: 'ACCOUNT-1' } })
    selectFile()
    fireEvent.click(screen.getByRole('button', { name: 'Prévisualiser le fichier' }))
    expect(await screen.findByText(/Ligne 2 : La devise/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('checkbox'))
    expect(screen.getByRole('button', { name: "Confirmer l'import" })).toBeDisabled()

    previewCsvImport.mockResolvedValue({ ...preview, alreadyImported: true, canImport: false, duplicateCount: 1 })
    fireEvent.click(screen.getByRole('button', { name: 'Prévisualiser le fichier' }))
    expect(await screen.findByText(/Ce fichier a déjà été importé/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: "Confirmer l'import" })).not.toBeInTheDocument()
  })

  it('signale une indisponibilité et un fichier invalide sans les confondre avec un import réussi', async () => {
    previewCsvImport.mockRejectedValue(new ApiError(503, 'unavailable'))
    start()
    fireEvent.change(screen.getByLabelText('Compte destinataire'), { target: { value: 'ACCOUNT-1' } })
    fireEvent.change(screen.getByLabelText('Fichier CSV'), { target: { files: [new File(['x'], 'file.xlsx')] } })
    expect(screen.getByRole('alert')).toHaveTextContent('CSV non vide')
    selectFile()
    fireEvent.click(screen.getByRole('button', { name: 'Prévisualiser le fichier' }))
    expect(await screen.findByRole('alert')).toHaveTextContent("service d'import est momentanément indisponible")
    expect(screen.queryByRole('heading', { name: 'Import terminé' })).not.toBeInTheDocument()
  })
})
