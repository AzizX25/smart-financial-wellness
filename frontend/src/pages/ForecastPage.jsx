import { useEffect, useRef, useState } from 'react'
import { Badge, Card, formatCurrency, InlineNotice, PageHeader } from '../components/Ui'
import { ApiError } from '../services/apiClient'
import { generateCurrentForecast, getForecast, listForecasts, simulateForecast } from '../services/forecastApi'

const CURRENCIES = ['TND', 'EUR', 'USD']
const EXPERIMENTAL_WARNING = 'Prévision expérimentale — La correction intelligente utilise actuellement un modèle entraîné sur des données synthétiques. Elle ne constitue pas une garantie.'
const UNAVAILABLE_MESSAGES = {
  NO_ELIGIBLE_ACCOUNTS: 'Aucun compte actif ne permet encore de préparer une prévision.',
  ACCOUNT_MISSING_SNAPSHOT: 'Un solde récent manque pour au moins un compte.',
  NO_COMMON_CUTOFF: "Les comptes ne disposent pas encore d'une date de solde commune.",
  CUTOFF_OUTSIDE_CURRENT_MONTH: "Le dernier solde commun ne se situe pas dans le mois en cours.",
}
const ML_MESSAGES = {
  INSUFFICIENT_HISTORY: "L'historique est encore insuffisant : la prévision déterministe reste disponible.",
  UNSUPPORTED_CURRENCY: 'La correction intelligente est limitée au TND : la prévision déterministe reste disponible.',
  SERVICE_UNAVAILABLE: 'Le service intelligent est momentanément indisponible : la prévision déterministe reste disponible.',
  INVALID_RESPONSE: "La réponse du modèle n'a pas été retenue : la prévision déterministe reste disponible.",
  MODEL_VERSION_MISMATCH: 'La version du modèle ne correspond pas à celle attendue : la prévision déterministe reste disponible.',
}

const currentForecastRequests = new Map()

function loadCurrentForecast(currency, timezone, reload) {
  const key = `${currency}:${timezone}:${reload}`
  if (!currentForecastRequests.has(key)) {
    const request = generateCurrentForecast(currency, timezone)
      .finally(() => currentForecastRequests.delete(key))
    currentForecastRequests.set(key, request)
  }
  return currentForecastRequests.get(key)
}

function browserTimeZone() {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || 'Africa/Tunis'
  } catch {
    return 'Africa/Tunis'
  }
}

function decimalValue(value) {
  const normalized = String(value ?? '').trim().replace(',', '.')
  if (!/^(?:0|[1-9]\d{0,14})(?:\.\d{1,4})?$/.test(normalized)) return null
  return normalized.includes('.') ? normalized : `${normalized}.0000`
}

function monthLabel(month) {
  if (!month) return ''
  const [year, monthNumber] = month.split('-').map(Number)
  return new Intl.DateTimeFormat('fr-FR', { month: 'long', year: 'numeric' })
    .format(new Date(year, monthNumber - 1, 1))
}

function dateLabel(value) {
  if (!value) return '—'
  return new Intl.DateTimeFormat('fr-FR', { dateStyle: 'long' })
    .format(new Date(`${value}T12:00:00`))
}

export default function ForecastPage() {
  const [currency, setCurrency] = useState('TND')
  const [reload, setReload] = useState(0)
  const [forecast, setForecast] = useState(null)
  const [history, setHistory] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [income, setIncome] = useState('0.0000')
  const [expense, setExpense] = useState('0.0000')
  const [simulation, setSimulation] = useState(null)
  const [simulationError, setSimulationError] = useState('')
  const [simulating, setSimulating] = useState(false)
  const requestSequence = useRef(0)
  const detailSequence = useRef(0)

  useEffect(() => {
    const sequence = ++requestSequence.current
    const timezone = browserTimeZone()
    setLoading(true)
    setError('')
    setForecast(null)
    setHistory([])
    setSimulation(null)
    Promise.all([loadCurrentForecast(currency, timezone, reload), listForecasts(currency, 0, 12)])
      .then(([current, page]) => {
        if (sequence !== requestSequence.current) return
        setForecast(current)
        setHistory(page.items || [])
      })
      .catch((requestError) => {
        if (sequence !== requestSequence.current) return
        setError(requestError instanceof ApiError ? requestError.message : 'La prévision ne peut pas être chargée pour le moment.')
      })
      .finally(() => {
        if (sequence === requestSequence.current) setLoading(false)
      })
  }, [currency, reload])

  async function selectHistory(id) {
    const sequence = ++detailSequence.current
    setError('')
    try {
      const selected = await getForecast(id)
      if (sequence !== detailSequence.current) return
      setForecast(selected)
      setSimulation(null)
    } catch (requestError) {
      if (sequence !== detailSequence.current) return
      setError(requestError instanceof ApiError ? requestError.message : 'Cette prévision ne peut pas être ouverte.')
    }
  }

  async function submitSimulation(event) {
    event.preventDefault()
    const additionalIncome = decimalValue(income)
    const additionalExpense = decimalValue(expense)
    if (!additionalIncome || !additionalExpense) {
      setSimulationError('Saisissez des montants positifs avec quatre décimales au maximum.')
      return
    }
    setSimulationError('')
    setSimulating(true)
    try {
      setSimulation(await simulateForecast(forecast.id, { additionalIncome, additionalExpense }))
    } catch (requestError) {
      setSimulationError(requestError instanceof ApiError ? requestError.message : 'La simulation ne peut pas être calculée pour le moment.')
    } finally {
      setSimulating(false)
    }
  }

  return (
    <div className="page">
      <PageHeader eyebrow="Prévision de fin de mois" title="Anticiper en toute simplicité" description="Une estimation claire à partir de vos soldes et de vos habitudes récentes." action={(
        <div className="forecast-toolbar">
          <label htmlFor="forecast-currency">Devise</label>
          <select id="forecast-currency" value={currency} onChange={(event) => setCurrency(event.target.value)}>{CURRENCIES.map((item) => <option key={item}>{item}</option>)}</select>
          <button className="button button--primary" type="button" onClick={() => setReload((value) => value + 1)} disabled={loading}>Actualiser</button>
        </div>
      )} />

      {loading && <Card aria-live="polite"><p>Préparation de votre prévision…</p></Card>}
      {error && <InlineNotice tone="error" live>{error}</InlineNotice>}
      {!loading && !error && forecast && !forecast.available && <Card className="forecast-empty" aria-live="polite"><h2>Prévision indisponible pour le moment</h2><p>{UNAVAILABLE_MESSAGES[forecast.unavailableReason] || 'Les données nécessaires ne sont pas encore disponibles.'}</p></Card>}

      {!loading && !error && forecast?.available && (
        <>
          {forecast.mlStatus === 'APPLIED' && <InlineNotice tone="warning" live>{EXPERIMENTAL_WARNING}</InlineNotice>}
          {forecast.mlStatus !== 'APPLIED' && ML_MESSAGES[forecast.mlStatus] && <InlineNotice tone="info" live>{ML_MESSAGES[forecast.mlStatus]}</InlineNotice>}
          <div className="forecast-metrics">
            <Card><p className="eyebrow">Solde au {dateLabel(forecast.cutoffDate)}</p><strong>{formatCurrency(forecast.startingBalance, forecast.currency)}</strong></Card>
            <Card><p className="eyebrow">Flux restant déterministe</p><strong>{formatCurrency(forecast.deterministicRemainingNetFlow, forecast.currency)}</strong></Card>
            <Card><p className="eyebrow">Solde déterministe estimé</p><strong>{formatCurrency(forecast.deterministicPredictedEndBalance, forecast.currency)}</strong></Card>
            <Card className="forecast-final"><p className="eyebrow">Solde final estimé</p><strong>{formatCurrency(forecast.finalPredictedEndBalance, forecast.currency)}</strong><small>Fin {monthLabel(forecast.forecastMonth)}</small></Card>
          </div>
          <div className="forecast-content-grid">
            <Card>
              <div className="card-heading"><div><p className="eyebrow">Explication</p><h2>Comment cette estimation est construite</h2></div><Badge tone={forecast.mlStatus === 'APPLIED' ? 'teal' : 'neutral'}>{forecast.mlStatus === 'APPLIED' ? 'Hybride' : 'Déterministe'}</Badge></div>
              <dl className="forecast-breakdown">
                <div><dt>Revenus restants estimés</dt><dd>{formatCurrency(forecast.deterministicRemainingIncome, forecast.currency)}</dd></div>
                <div><dt>Dépenses restantes estimées</dt><dd>{formatCurrency(forecast.deterministicRemainingExpense, forecast.currency)}</dd></div>
                {forecast.mlStatus === 'APPLIED' && <div><dt>Correction expérimentale</dt><dd>{formatCurrency(forecast.mlPredictedResidual, forecast.currency)}</dd></div>}
              </dl>
              <details className="forecast-details"><summary>Voir les méthodes utilisées</summary><p>Méthode déterministe : {forecast.deterministicMethod} v{forecast.deterministicMethodVersion}</p>{forecast.modelId && <p>Modèle : {forecast.modelId} v{forecast.modelVersion} — contrat {forecast.featureVersion}</p>}<p>Cette estimation n'est jamais une garantie.</p></details>
            </Card>
            <Card>
              <p className="eyebrow">Historique récent</p><h2>Prévisions enregistrées</h2>
              {history.length === 0 ? <p className="muted">Aucune prévision précédente dans cette devise.</p> : <ul className="forecast-history">{history.map((item) => <li key={item.id}><button type="button" onClick={() => selectHistory(item.id)} aria-current={item.id === forecast.id ? 'true' : undefined}><span>{monthLabel(item.forecastMonth)} · {dateLabel(item.cutoffDate)}</span><strong>{formatCurrency(item.finalPredictedEndBalance, item.currency)}</strong></button></li>)}</ul>}
            </Card>
          </div>
          <Card className="forecast-simulator">
            <div className="card-heading"><div><p className="eyebrow">Simulation temporaire</p><h2>Tester un changement</h2><p className="muted">Le résultat est calculé par le serveur et n'est pas enregistré.</p></div><Badge tone="gold">Non garanti</Badge></div>
            <form className="forecast-simulation-form" onSubmit={submitSimulation}>
              <label className="field"><span>Revenu supplémentaire</span><input value={income} inputMode="decimal" onChange={(event) => setIncome(event.target.value)} aria-describedby="forecast-simulation-help" /></label>
              <label className="field"><span>Dépense supplémentaire</span><input value={expense} inputMode="decimal" onChange={(event) => setExpense(event.target.value)} aria-describedby="forecast-simulation-help" /></label>
              <button className="button button--primary" type="submit" disabled={simulating}>{simulating ? 'Calcul en cours…' : 'Simuler'}</button>
            </form>
            <p id="forecast-simulation-help" className="muted">Montants positifs, quatre décimales au maximum.</p>
            {simulationError && <InlineNotice tone="error" live>{simulationError}</InlineNotice>}
            {simulation && <div className="forecast-simulation-result" aria-live="polite"><div><span>Prévision de base</span><strong>{formatCurrency(simulation.basePredictedEndBalance, simulation.currency)}</strong></div><div><span>Revenu ajouté</span><strong>+ {formatCurrency(simulation.additionalIncome, simulation.currency)}</strong></div><div><span>Dépense ajoutée</span><strong>− {formatCurrency(simulation.additionalExpense, simulation.currency)}</strong></div><div><span>Résultat simulé</span><strong>{formatCurrency(simulation.simulatedEndBalance, simulation.currency)}</strong><small>Non garanti</small></div></div>}
          </Card>
        </>
      )}
    </div>
  )
}
