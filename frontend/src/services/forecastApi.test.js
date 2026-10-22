import { beforeEach, describe, expect, it, vi } from 'vitest'
import { apiRequest } from './apiClient'
import { generateCurrentForecast, getForecast, listForecasts, simulateForecast } from './forecastApi'

vi.mock('./apiClient', () => ({ apiRequest: vi.fn() }))

describe('forecastApi', () => {
  beforeEach(() => vi.clearAllMocks())

  it('uses the shared authenticated client and encodes every parameter', () => {
    generateCurrentForecast('TND', 'Africa/Tunis')
    listForecasts('EUR', 2, 8)
    getForecast('id/with space')
    simulateForecast('id/with space', { additionalIncome: '1.0000', additionalExpense: '0.0000' })

    expect(apiRequest).toHaveBeenNthCalledWith(1, '/api/forecasts/current?currency=TND&timezone=Africa%2FTunis', { method: 'POST' })
    expect(apiRequest).toHaveBeenNthCalledWith(2, '/api/forecasts?currency=EUR&page=2&size=8')
    expect(apiRequest).toHaveBeenNthCalledWith(3, '/api/forecasts/id%2Fwith%20space')
    expect(apiRequest).toHaveBeenNthCalledWith(4, '/api/forecasts/id%2Fwith%20space/simulations', {
      method: 'POST',
      body: { additionalIncome: '1.0000', additionalExpense: '0.0000' },
    })
  })
})
