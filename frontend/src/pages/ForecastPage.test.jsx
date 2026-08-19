import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { generateCurrentForecast, getForecast, listForecasts, simulateForecast } from '../services/forecastApi'
import ForecastPage from './ForecastPage'

vi.mock('../services/forecastApi', () => ({
  generateCurrentForecast: vi.fn(),
  getForecast: vi.fn(),
  listForecasts: vi.fn(),
  simulateForecast: vi.fn(),
}))

const FORECAST_ID = '11111111-1111-1111-1111-111111111111'
const EXPERIMENTAL_WARNING = 'Prévision expérimentale — La correction intelligente utilise actuellement un modèle entraîné sur des données synthétiques. Elle ne constitue pas une garantie.'

function forecast(overrides = {}) {
  return {
    available: true,
    unavailableReason: null,
    id: FORECAST_ID,
    currency: 'TND',
    timeZone: 'Africa/Tunis',
    forecastMonth: '2026-08',
    cutoffDate: '2026-08-18',
    startingBalance: '1200.0000',
    deterministicRemainingIncome: '800.0000',
    deterministicRemainingExpense: '500.0000',
    deterministicRemainingNetFlow: '300.0000',
    deterministicPredictedEndBalance: '1500.0000',
    mlStatus: 'APPLIED',
    mlPredictedResidual: '-75.2500',
    finalPredictedEndBalance: '1424.7500',
    deterministicMethod: 'HISTORICAL_MONTHLY_AVERAGE',
    deterministicMethodVersion: '1',
    modelId: 'forecast-huber',
    modelVersion: '1',
    featureVersion: 'forecast-residual-features-v1',
    experimental: true,
    syntheticTrainingData: true,
    guaranteed: false,
    createdAt: '2026-08-18T10:00:00Z',
    ...overrides,
  }
}

function deferred() {
  let resolve
  const promise = new Promise((promiseResolve) => { resolve = promiseResolve })
  return { promise, resolve }
}

beforeEach(() => {
  vi.clearAllMocks()
  generateCurrentForecast.mockResolvedValue(forecast())
  listForecasts.mockResolvedValue({ items: [forecast()], page: 0, size: 12, totalElements: 1, totalPages: 1 })
})

describe('ForecastPage', () => {
  it('shows the applied model, its experimental warning, metadata, and history', async () => {
    render(<ForecastPage />)

    expect(await screen.findByText(EXPERIMENTAL_WARNING)).toBeInTheDocument()
    expect(screen.getByText(/Correction expérimentale/)).toBeInTheDocument()
    expect(screen.getByText(/Modèle : forecast-huber v1/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /août 2026/ })).toBeInTheDocument()
    expect(generateCurrentForecast).toHaveBeenCalledWith('TND', expect.any(String))
    expect(listForecasts).toHaveBeenCalledWith('TND', 0, 12)
  })

  it.each([
    ['INSUFFICIENT_HISTORY', "L'historique est encore insuffisant"],
    ['UNSUPPORTED_CURRENCY', 'limitée au TND'],
    ['SERVICE_UNAVAILABLE', 'momentanément indisponible'],
    ['INVALID_RESPONSE', "n'a pas été retenue"],
  ])('keeps the deterministic result visible for %s', async (status, message) => {
    generateCurrentForecast.mockResolvedValue(forecast({ mlStatus: status, mlPredictedResidual: null, finalPredictedEndBalance: '1500.0000', modelId: null, modelVersion: null, featureVersion: null, experimental: false, syntheticTrainingData: false }))
    render(<ForecastPage />)

    expect(await screen.findByText(new RegExp(message))).toBeInTheDocument()
    expect(screen.getByText(/Solde final estimé/)).toBeInTheDocument()
    expect(screen.queryByText(EXPERIMENTAL_WARNING)).not.toBeInTheDocument()
  })

  it('distinguishes unavailable data from an API failure', async () => {
    generateCurrentForecast.mockResolvedValue({ available: false, unavailableReason: 'ACCOUNT_MISSING_SNAPSHOT' })
    listForecasts.mockResolvedValue({ items: [] })
    render(<ForecastPage />)

    expect(await screen.findByText('Prévision indisponible pour le moment')).toBeInTheDocument()
    expect(screen.getByText(/solde récent manque/)).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('submits scale-4 adjustments to the server and displays its authoritative result', async () => {
    simulateForecast.mockResolvedValue({
      forecastId: FORECAST_ID,
      currency: 'TND',
      basePredictedEndBalance: '1424.7500',
      additionalIncome: '100.0000',
      additionalExpense: '25.5000',
      simulatedEndBalance: '1499.2500',
      guaranteed: false,
    })
    render(<ForecastPage />)
    await screen.findByText(EXPERIMENTAL_WARNING)

    fireEvent.change(screen.getByLabelText('Revenu supplémentaire'), { target: { value: '100' } })
    fireEvent.change(screen.getByLabelText('Dépense supplémentaire'), { target: { value: '25,5' } })
    fireEvent.click(screen.getByRole('button', { name: 'Simuler' }))

    await waitFor(() => expect(simulateForecast).toHaveBeenCalledWith(FORECAST_ID, {
      additionalIncome: '100.0000',
      additionalExpense: '25.5',
    }))
    expect(await screen.findByText('Résultat simulé')).toBeInTheDocument()
    expect(screen.getByText('Non garanti', { selector: 'small' })).toBeInTheDocument()
  })

  it('rejects more than four fractional digits before calling the server', async () => {
    render(<ForecastPage />)
    await screen.findByText(EXPERIMENTAL_WARNING)
    fireEvent.change(screen.getByLabelText('Revenu supplémentaire'), { target: { value: '1.12345' } })
    fireEvent.click(screen.getByRole('button', { name: 'Simuler' }))

    expect(await screen.findByText(/^Saisissez des montants positifs/)).toBeInTheDocument()
    expect(simulateForecast).not.toHaveBeenCalled()
  })

  it('does not let an older currency response replace the newer selection', async () => {
    const oldRequest = deferred()
    generateCurrentForecast
      .mockReturnValueOnce(oldRequest.promise)
      .mockResolvedValueOnce(forecast({ currency: 'EUR', mlStatus: 'UNSUPPORTED_CURRENCY', mlPredictedResidual: null, finalPredictedEndBalance: '900.0000' }))
    listForecasts.mockResolvedValue({ items: [] })
    render(<ForecastPage />)

    fireEvent.change(screen.getByLabelText('Devise'), { target: { value: 'EUR' } })
    expect(await screen.findByText(/limitée au TND/)).toBeInTheDocument()
    oldRequest.resolve(forecast())
    await Promise.resolve()

    expect(screen.getByLabelText('Devise')).toHaveValue('EUR')
    expect(screen.queryByText(EXPERIMENTAL_WARNING)).not.toBeInTheDocument()
  })

  it('loads a selected saved forecast from the server', async () => {
    const saved = forecast({ id: '22222222-2222-2222-2222-222222222222', cutoffDate: '2026-08-10', finalPredictedEndBalance: '1300.0000' })
    listForecasts.mockResolvedValue({ items: [saved] })
    getForecast.mockResolvedValue(saved)
    render(<ForecastPage />)

    fireEvent.click(await screen.findByRole('button', { name: /10 août 2026/ }))
    await waitFor(() => expect(getForecast).toHaveBeenCalledWith(saved.id))
  })
})
