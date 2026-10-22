import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { extractReceipt } from '../services/receiptApi'
import ReceiptPanel from './ReceiptPanel'

vi.mock('../services/receiptApi', () => ({ extractReceipt: vi.fn() }))
const file = () => new File(['synthetic image'], 'receipt.png', { type: 'image/png' })
const response = { status: 'EXTRACTED', merchant: 'Synthetic café', date: '2026-10-01', amount: '12.3456', currency: 'TND', asOfDate: '2026-10-11' }
const exportsMade = () => URL.createObjectURL.mock.calls.filter(([blob]) => !(blob instanceof File))

describe('lecture de ticket sans import automatique', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    extractReceipt.mockResolvedValue(response)
    URL.createObjectURL = vi.fn((blob) => blob instanceof File ? 'blob:receipt-preview' : 'blob:synthetic')
    URL.revokeObjectURL = vi.fn()
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
  })

  function choose() {
    fireEvent.click(screen.getByText('Lire un ticket — IA locale expérimentale'))
    fireEvent.change(screen.getByLabelText(/Photo du ticket/), { target: { files: [file()] } })
    fireEvent.click(screen.getByRole('button', { name: 'Lire ce ticket' }))
  }

  it('exige une vérification humaine et exporte sans enregistrer de transaction', async () => {
    render(<ReceiptPanel />)
    choose()
    expect(await screen.findByLabelText('Commerçant')).toHaveValue('Synthetic café')
    const exportButton = screen.getByRole('button', { name: 'Télécharger le CSV vérifié' })
    expect(exportButton).toBeDisabled()
    fireEvent.click(screen.getByRole('checkbox'))
    fireEvent.change(screen.getByLabelText('Montant total'), { target: { value: '15.1234' } })
    expect(exportButton).toBeDisabled()
    fireEvent.click(screen.getByRole('checkbox'))
    fireEvent.click(exportButton)
    expect(await screen.findByText(/CSV préparé/)).toBeInTheDocument()
    expect(exportsMade()).toHaveLength(1)
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:synthetic')
    expect(extractReceipt).toHaveBeenCalledTimes(1)
    expect(exportButton).toBeDisabled()
  })

  it('ne suppose pas une devise absente et refuse un montant à cinq décimales', async () => {
    extractReceipt.mockResolvedValue({ ...response, status: 'PARTIAL', currency: null })
    render(<ReceiptPanel />)
    choose()
    expect(await screen.findByLabelText('Devise imprimée')).toHaveValue('')
    fireEvent.click(screen.getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: 'Télécharger le CSV vérifié' }))
    expect(screen.getByLabelText('Devise imprimée')).toHaveAccessibleDescription(/^Confirmez la devise/)
    fireEvent.change(screen.getByLabelText('Devise imprimée'), { target: { value: 'TND' } })
    fireEvent.change(screen.getByLabelText('Montant total'), { target: { value: '12.12345' } })
    fireEvent.click(screen.getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: 'Télécharger le CSV vérifié' }))
    expect(screen.getByLabelText('Montant total')).toHaveAccessibleDescription(/Indiquez un montant strictement positif avec au maximum quatre décimales/)
    expect(exportsMade()).toHaveLength(0)
  })

  it('présente l’indisponibilité sans résultats fictifs et empêche les requêtes répétées', async () => {
    let resolve
    extractReceipt.mockReturnValueOnce(new Promise((done) => { resolve = done }))
    render(<ReceiptPanel />)
    choose()
    expect(screen.getByRole('button', { name: 'Lecture locale en cours…' })).toBeDisabled()
    resolve({ status: 'UNAVAILABLE', asOfDate: '2026-10-11' })
    expect(await screen.findByRole('alert')).toHaveTextContent('indisponible')
    expect(screen.queryByLabelText('Commerçant')).not.toBeInTheDocument()
    expect(extractReceipt).toHaveBeenCalledTimes(1)
  })

  it('keeps valid fields when a date is discarded and requires a corrected date and renewed human review before export', async () => {
    extractReceipt.mockResolvedValue({ status: 'PARTIAL', merchant: 'Lefties', date: null, amount: '309.700', currency: 'TND', asOfDate: '2026-10-14' })
    render(<ReceiptPanel />)
    choose()
    expect(await screen.findByLabelText('Commerçant')).toHaveValue('Lefties')
    expect(screen.getByLabelText('Montant total')).toHaveValue('309.700')
    expect(screen.getByLabelText('Devise imprimée')).toHaveValue('TND')
    const date = screen.getByLabelText('Date de l’achat')
    expect(date).toHaveValue('')
    expect(date).toHaveAttribute('max', '2026-10-14')
    expect(screen.getByText(/La date n’a pas pu être validée/)).toBeInTheDocument()
    const exportButton = screen.getByRole('button', { name: 'Télécharger le CSV vérifié' })
    fireEvent.click(screen.getByRole('checkbox'))
    fireEvent.click(exportButton)
    expect(date).toHaveAccessibleDescription(/Indiquez une date valide, pas après la date du jour/)
    expect(exportsMade()).toHaveLength(0)
    fireEvent.change(date, { target: { value: '2026-11-09' } })
    fireEvent.click(screen.getByRole('checkbox'))
    fireEvent.click(exportButton)
    expect(date).toHaveAttribute('aria-invalid', 'true')
    expect(exportsMade()).toHaveLength(0)
    fireEvent.change(date, { target: { value: '2026-09-11' } })
    expect(exportButton).toBeDisabled()
    fireEvent.click(screen.getByRole('checkbox'))
    fireEvent.click(exportButton)
    expect(await screen.findByText(/CSV préparé/)).toBeInTheDocument()
    expect(exportsMade()).toHaveLength(1)
    expect(extractReceipt).toHaveBeenCalledTimes(1)
  })

  it('refuse un format non supporté avant l’appel API', async () => {
    render(<ReceiptPanel />)
    fireEvent.click(screen.getByText('Lire un ticket — IA locale expérimentale'))
    fireEvent.change(screen.getByLabelText(/Photo du ticket/), { target: { files: [new File(['pdf'], 'receipt.pdf', { type: 'application/pdf' })] } })
    fireEvent.click(screen.getByRole('button', { name: 'Lire ce ticket' }))
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('JPEG ou PNG'))
    expect(extractReceipt).not.toHaveBeenCalled()
  })

  it('shows only a local image and releases its object URL on replacement and unmount', async () => {
    const { unmount } = render(<ReceiptPanel />)
    choose()
    expect(await screen.findByAltText('Aperçu local du ticket à vérifier')).toHaveAttribute('src', 'blob:receipt-preview')
    expect(URL.createObjectURL.mock.calls[0][0]).toBeInstanceOf(File)
    expect(await screen.findByLabelText('Date de l’achat')).toHaveValue('2026-10-01')
    fireEvent.change(screen.getByLabelText(/Photo du ticket/), { target: { files: [file()] } })
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:receipt-preview')
    expect(screen.queryByLabelText('Commerçant')).not.toBeInTheDocument()
    unmount()
    expect(URL.revokeObjectURL.mock.calls.filter(([url]) => url === 'blob:receipt-preview')).toHaveLength(2)
    expect(exportsMade()).toHaveLength(0)
  })

  it('identifies each missing field and clears warnings when corrected without assuming currency', async () => {
    extractReceipt.mockResolvedValue({ ...response, status: 'PARTIAL', date: null, amount: null, currency: null })
    render(<ReceiptPanel />)
    choose()
    const date = await screen.findByLabelText('Date de l’achat')
    const amount = screen.getByLabelText('Montant total')
    const currency = screen.getByLabelText('Devise imprimée')
    for (const field of [date, amount, currency]) expect(field).toHaveAttribute('aria-invalid', 'true')
    expect(currency).toHaveValue('')
    fireEvent.change(date, { target: { value: '2026-09-11' } })
    fireEvent.change(amount, { target: { value: '309,700' } })
    fireEvent.change(currency, { target: { value: 'TND' } })
    for (const field of [date, amount, currency]) expect(field).toHaveAttribute('aria-invalid', 'false')
    expect(amount).toHaveAccessibleDescription(/Total payé, taxes et timbre compris/)
    expect(screen.getByRole('button', { name: 'Télécharger le CSV vérifié' })).toBeDisabled()
    expect(exportsMade()).toHaveLength(0)
  })

  it('ignores extraction completed after another file is selected', async () => {
    let resolve
    extractReceipt.mockReturnValueOnce(new Promise((done) => { resolve = done }))
    render(<ReceiptPanel />)
    choose()
    fireEvent.change(screen.getByLabelText(/Photo du ticket/), { target: { files: [file()] } })
    resolve(response)
    await waitFor(() => expect(screen.getByRole('button', { name: 'Lire ce ticket' })).not.toBeDisabled())
    expect(screen.queryByLabelText('Commerçant')).not.toBeInTheDocument()
    expect(exportsMade()).toHaveLength(0)
  })
})
