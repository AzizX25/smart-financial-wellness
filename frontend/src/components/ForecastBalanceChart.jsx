import { useEffect, useId, useRef, useState } from 'react'
import { Card, formatCurrency, formatScale4Display } from './Ui'

function moneyUnits(value) {
  const match = String(value ?? '').match(/^(-?)(\d+)(?:\.(\d{1,4}))?$/)
  if (!match) return null
  const units = BigInt(match[2]) * 10000n + BigInt((match[3] || '').padEnd(4, '0'))
  return match[1] ? -units : units
}

function shortDate(date) {
  return new Intl.DateTimeFormat('fr-FR', { day: 'numeric', month: 'short', timeZone: 'UTC' }).format(date)
}

export default function ForecastBalanceChart({ forecast, simulation }) {
  const titleId = useId()
  const descriptionId = useId()
  const container = useRef(null)
  const [width, setWidth] = useState(640)

  useEffect(() => {
    if (!container.current || typeof ResizeObserver === 'undefined') return undefined
    const observer = new ResizeObserver(([entry]) => {
      if (entry.contentRect.width > 0) setWidth(entry.contentRect.width)
    })
    observer.observe(container.current)
    return () => observer.disconnect()
  }, [])

  const start = moneyUnits(forecast.startingBalance)
  const end = moneyUnits(forecast.finalPredictedEndBalance)
  const simulated = simulation?.forecastId === forecast.id && simulation.currency === forecast.currency
    ? moneyUnits(simulation.simulatedEndBalance) : null
  const cutoff = new Date(`${forecast.cutoffDate}T00:00:00Z`)
  const [year, month] = String(forecast.forecastMonth).split('-').map(Number)
  const lastDay = new Date(Date.UTC(year, month, 0))
  const valid = start !== null && end !== null && Number.isFinite(cutoff.getTime())
    && Number.isFinite(lastDay.getTime()) && month >= 1 && month <= 12
    && cutoff.getUTCFullYear() === year && cutoff.getUTCMonth() === month - 1

  if (!valid) return <Card><p>Le graphique n’est pas disponible pour cette prévision.</p></Card>

  // Scale-4 integers preserve the amounts. Only ratios used for SVG coordinates become Numbers.
  const values = [0n, start, end, ...(simulated === null ? [] : [simulated])]
  const minimum = values.reduce((a, b) => a < b ? a : b)
  const maximum = values.reduce((a, b) => a > b ? a : b)
  const padding = (maximum - minimum) / 10n || 10000n
  const low = minimum < 0n ? minimum - padding : 0n
  const high = maximum + padding
  const left = width < 480 ? 78 : 96
  const right = width - 22
  const top = 32
  const bottom = 218
  const y = (value) => bottom - Number((value - low) * 100000n / (high - low)) / 100000 * (bottom - top)
  const sameDay = cutoff.getTime() === lastDay.getTime()
  const startX = sameDay ? (left + right) / 2 : left
  const endX = sameDay ? startX : right
  const ticks = Array.from({ length: 5 }, (_, index) => low + (high - low) * BigInt(index) / 4n)
  const dates = sameDay ? [cutoff] : [cutoff, lastDay]
  const rows = [
    ['Solde observé', cutoff, forecast.startingBalance],
    ['Prévision de fin de mois', lastDay, forecast.finalPredictedEndBalance],
    ...(simulated === null ? [] : [['Simulation temporaire', lastDay, simulation.simulatedEndBalance]]),
  ]

  return (
    <Card as="figure" className="forecast-balance-chart" aria-labelledby={titleId}>
      <div className="card-heading"><div><p className="eyebrow">Votre solde dans le temps</p><h2 id={titleId}>Du solde actuel à la fin du mois</h2></div></div>
      <div ref={container} className="forecast-balance-chart__plot">
        <svg viewBox={`0 0 ${width} 270`} role="img" aria-label={`Évolution estimée du solde en ${forecast.currency}`} aria-describedby={descriptionId}>
          <text x="0" y="15" className="forecast-balance-chart__axis-title">Solde ({forecast.currency})</text>
          {ticks.map((value) => (
            <g key={String(value)}>
              <line x1={left} y1={y(value)} x2={right} y2={y(value)} className="forecast-balance-chart__grid" />
              <text x={left - 10} y={y(value) + 4} textAnchor="end">{formatScale4Display(value, forecast.currency).replace(` ${forecast.currency}`, '')}</text>
            </g>
          ))}
          <path d={`M${left} ${top} V${bottom} H${right}`} className="forecast-balance-chart__axis" />
          {minimum < 0n && <line x1={left} y1={y(0n)} x2={right} y2={y(0n)} className="forecast-balance-chart__zero" />}
          <line x1={startX} y1={y(start)} x2={endX} y2={y(end)} className={`forecast-balance-chart__estimate${end < 0n ? ' is-negative' : ''}`} />
          {simulated !== null && <line x1={startX} y1={y(start)} x2={endX} y2={y(simulated)} className="forecast-balance-chart__simulation" />}
          <circle cx={startX} cy={y(start)} r="5" className="forecast-balance-chart__point"><title>{`Solde observé : ${formatCurrency(forecast.startingBalance, forecast.currency)}`}</title></circle>
          <path d={`M${endX} ${y(end) - 6} l6 6 -6 6 -6 -6Z`} className={`forecast-balance-chart__point${end < 0n ? ' is-negative' : ''}`}><title>{`Prévision : ${formatCurrency(forecast.finalPredictedEndBalance, forecast.currency)}`}</title></path>
          {simulated !== null && <rect x={endX - 5} y={y(simulated) - 5} width="10" height="10" className="forecast-balance-chart__simulation-point"><title>{`Simulation : ${formatCurrency(simulation.simulatedEndBalance, forecast.currency)}`}</title></rect>}
          {dates.map((date, index) => <text key={date.toISOString()} x={index === 0 ? startX : endX} y={bottom + 22} textAnchor={sameDay ? 'middle' : index === 0 ? 'start' : 'end'}>{shortDate(date)}</text>)}
          <text x={(left + right) / 2} y="264" textAnchor="middle" className="forecast-balance-chart__axis-title">Date</text>
        </svg>
      </div>
      <figcaption id={descriptionId}>Le point de départ est un solde observé ; le point final est l’estimation du serveur. Le trait pointillé les relie à titre indicatif : il ne représente pas des prévisions quotidiennes.</figcaption>
      <div className="forecast-balance-chart__legend"><span>● Solde observé</span><span>◆ Prévision</span>{simulated !== null && <span>■ Simulation temporaire</span>}</div>
      <details className="forecast-chart-values"><summary>Valeurs du graphique</summary><table><caption className="sr-only">Soldes du graphique en {forecast.currency}</caption><thead><tr><th scope="col">Repère</th><th scope="col">Date</th><th scope="col">Montant</th></tr></thead><tbody>{rows.map(([label, date, amount]) => <tr key={label}><th scope="row">{label}</th><td>{shortDate(date)}</td><td>{formatCurrency(amount, forecast.currency)}</td></tr>)}</tbody></table></details>
    </Card>
  )
}
