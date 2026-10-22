import { fireEvent, render, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import ForecastBalanceChart from './ForecastBalanceChart'

const forecast = {
  id: 'forecast-tnd', currency: 'TND', forecastMonth: '2026-08', cutoffDate: '2026-08-18',
  startingBalance: '1200.0000', finalPredictedEndBalance: '1424.7500',
}

describe('graphique de prévision autoritaire', () => {
  it('affiche deux repères datés avec des axes lisibles et sans inventer de prévisions quotidiennes', () => {
    render(<ForecastBalanceChart forecast={forecast} />)
    const chart = screen.getByRole('img', { name: 'Évolution estimée du solde en TND' })
    expect(within(chart).getByText('Solde (TND)')).toBeInTheDocument()
    expect(within(chart).getByText('Date')).toBeInTheDocument()
    expect(within(chart).getByText('18 août')).toBeInTheDocument()
    expect(within(chart).getByText('31 août')).toBeInTheDocument()
    expect(screen.getByText(/ne représente pas des prévisions quotidiennes/)).toBeInTheDocument()
    fireEvent.click(screen.getByText('Valeurs du graphique'))
    const table = screen.getByRole('table', { name: 'Soldes du graphique en TND' })
    expect(within(table).getAllByRole('row')).toHaveLength(3)
    expect(within(table).getByText('1 424,8 TND', { exact: false })).toBeInTheDocument()
  })

  it.each(['-400.5000', '0.0000'])('représente un solde final %s sans coordonnées invalides', (amount) => {
    const { container } = render(<ForecastBalanceChart forecast={{ ...forecast, startingBalance: '0.0000', finalPredictedEndBalance: amount }} />)
    const chart = screen.getByRole('img')
    expect(chart.outerHTML).not.toMatch(/NaN|Infinity/)
    expect(container.querySelector('.forecast-balance-chart__estimate').classList.contains('is-negative')).toBe(amount.startsWith('-'))
  })

  it('n’affiche une simulation que pour la même prévision et la même devise', () => {
    const simulation = { forecastId: forecast.id, currency: 'TND', simulatedEndBalance: '1600.1250' }
    const view = render(<ForecastBalanceChart forecast={forecast} simulation={simulation} />)
    expect(screen.getByText('■ Simulation temporaire')).toBeInTheDocument()
    view.rerender(<ForecastBalanceChart forecast={forecast} simulation={{ ...simulation, currency: 'EUR' }} />)
    expect(screen.queryByText('■ Simulation temporaire')).not.toBeInTheDocument()
    view.rerender(<ForecastBalanceChart forecast={forecast} simulation={{ ...simulation, forecastId: 'another' }} />)
    expect(screen.queryByText('■ Simulation temporaire')).not.toBeInTheDocument()
  })

  it('gère la fin du mois et rejette les montants ou périodes incomplets', () => {
    const view = render(<ForecastBalanceChart forecast={{ ...forecast, cutoffDate: '2026-08-31' }} />)
    expect(within(screen.getByRole('img')).getAllByText('31 août')).toHaveLength(1)
    view.rerender(<ForecastBalanceChart forecast={{ ...forecast, startingBalance: null }} />)
    expect(screen.queryByRole('img')).not.toBeInTheDocument()
    expect(screen.getByText(/graphique n’est pas disponible/)).toBeInTheDocument()
    view.rerender(<ForecastBalanceChart forecast={{ ...forecast, cutoffDate: '2026-07-31' }} />)
    expect(screen.queryByRole('img')).not.toBeInTheDocument()
  })
})
