import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { explainRecommendation, generateRecommendations, setRecommendationStatus } from '../services/recommendationApi'
import { Badge, Card, InlineNotice } from './Ui'

const priorities = { HIGH: 'Prioritaire', MEDIUM: 'À examiner', LOW: 'À votre rythme' }
const states = { ACCEPTED: 'Prise en compte', REJECTED: 'Non retenue', HIDDEN: 'Masquée' }
const allowedPaths = new Set(['/planification-salaire', '/objectifs'])

function factLabel(fact) {
  if (!fact || fact.value === null || fact.value === undefined) return 'Information indisponible'
  const percent = fact.unit === 'RATIO'
  return new Intl.NumberFormat('fr-TN', percent
    ? { style: 'percent', minimumFractionDigits: 1, maximumFractionDigits: 1 }
    : { minimumFractionDigits: 1, maximumFractionDigits: 1 }).format(fact.value) + (fact.unit === 'PERCENT' ? ' %' : '')
}

export default function RecommendationsSection({ currency, dataVersion }) {
  const [result, setResult] = useState(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(null)
  const [reload, setReload] = useState(0)
  const [explanations, setExplanations] = useState({})
  const sequence = useRef(0)
  const shown = result?.currency === currency ? result : null

  useEffect(() => {
    const request = ++sequence.current
    setResult(null)
    setBusy(null)
    setExplanations({})
    setError('')
    if (!currency) { setLoading(false); return undefined }
    setLoading(true)
    generateRecommendations(currency)
      .then((response) => { if (sequence.current === request) setResult(response) })
      .catch(() => {
        if (sequence.current === request) setError('Vos pistes personnalisées ne peuvent pas être chargées pour le moment.')
      })
      .finally(() => { if (sequence.current === request) setLoading(false) })
    return () => { if (sequence.current === request) sequence.current += 1 }
  }, [currency, dataVersion, reload])

  async function feedback(id, status) {
    if (busy) return
    const request = sequence.current
    setBusy(id)
    setError('')
    try {
      const response = await setRecommendationStatus(id, status)
      if (sequence.current === request) {
        setResult((current) => ({ ...current, items: current.items.map((item) => item.id === id ? response : item) }))
      }
    } catch {
      if (sequence.current === request) setError('Votre retour n’a pas été enregistré. La piste reste disponible.')
    } finally {
      if (sequence.current === request) setBusy(null)
    }
  }

  async function explain(item) {
    if (busy) return
    const request = sequence.current
    setBusy(item.id)
    try {
      const response = await explainRecommendation(item.id)
      if (sequence.current === request && response?.factCode === item.fact.code) {
        setExplanations((current) => ({ ...current, [item.id]: response }))
      }
    } catch {
      if (sequence.current === request) {
        setExplanations((current) => ({ ...current, [item.id]: { source: 'RULES', text: item.message } }))
      }
    } finally {
      if (sequence.current === request) setBusy(null)
    }
  }

  const active = shown?.items?.filter((item) => item.status === 'ACTIVE') || []
  return (
    <section className="section-block recommendations-section" aria-labelledby="recommendations-title">
      <div className="section-heading">
        <div><p className="eyebrow">Des faits, puis des pistes</p><h2 id="recommendations-title">Mes prochaines étapes</h2></div>
        {currency && <Badge tone="blue">{currency}</Badge>}
      </div>
      {!currency && <p className="muted">Ajoutez des données financières pour obtenir des pistes adaptées.</p>}
      {loading && <p role="status">Préparation de vos pistes…</p>}
      {error && <InlineNotice live tone="warning">{error} <button className="button button--secondary" disabled={loading} type="button" onClick={() => setReload((value) => value + 1)}>Réessayer</button></InlineNotice>}
      {shown?.status === 'INSUFFICIENT_DATA' && <Card><h3>Un peu plus d’historique est nécessaire</h3><p>Ces pistes reposent sur les facteurs disponibles de votre indicateur. Aucun conseil n’est inventé lorsque les données manquent.</p></Card>}
      {shown?.status === 'NO_ACTION_NEEDED' && <Card><h3>Aucune piste prioritaire détectée</h3><p>Les règles disponibles ne signalent pas de point particulier à revoir sur cette période. Ce n’est pas une garantie sur votre situation.</p></Card>}
      {active.length > 0 && (
        <div className="recommendation-grid">
          {active.slice(0, 3).map((item) => (
            <div key={item.id} className="recommendation-item">
            <Card className="recommendation-card">
              <Badge tone={item.priority === 'HIGH' ? 'gold' : 'blue'}>{priorities[item.priority] || 'À examiner'}</Badge>
              <h3>{item.title}</h3>
              <p>{item.message}</p>
              <button className="button button--secondary" type="button" disabled={busy !== null} aria-expanded={Boolean(explanations[item.id])} aria-controls={explanations[item.id] ? `explanation-${item.id}` : undefined} onClick={() => explanations[item.id]
                ? setExplanations((current) => ({ ...current, [item.id]: null }))
                : explain(item)}>{explanations[item.id] ? 'Fermer l’explication' : 'Expliquer simplement'}</button>
              {allowedPaths.has(item.actionPath) && <Link className="button button--primary" to={item.actionPath}>{item.actionLabel}</Link>}
            </Card>
            {explanations[item.id] && <div className="inline-panel recommendation-inline-panel" id={`explanation-${item.id}`}><InlineNotice live><strong>{explanations[item.id].source === 'LOCAL_LLM' ? 'Explication IA locale — expérimentale' : 'Explication déterministe'}</strong><p>{explanations[item.id].text}</p></InlineNotice></div>}
            </div>
          ))}
        </div>
      )}
      {shown?.status === 'AVAILABLE' && active.length === 0 && <p className="muted">Vous avez déjà donné votre retour sur toutes les pistes de cette évaluation.</p>}
      {shown?.items?.length > 0 && (
        <details className="recommendation-details">
          <summary>Comprendre les pistes et donner mon avis</summary>
          {shown.items.map((item) => (
            <article className="recommendation-detail" key={item.id}>
              <div><h3>{item.title}</h3><Badge tone="neutral">{states[item.status] || priorities[item.priority] || item.status}</Badge></div>
              <p>{item.fact.explanation} <strong>{factLabel(item.fact)}</strong></p>
              <p className="muted">Période : {item.periodStart} au {item.periodEnd} · {item.currency} · méthode {item.rulesVersion}</p>
              <p>{item.message}</p>
              {item.status === 'ACTIVE' && (
                <div className="button-row">
                  <button className="button button--secondary" type="button" disabled={busy !== null} onClick={() => feedback(item.id, 'ACCEPTED')}>Prise en compte</button>
                  <button className="button button--secondary" type="button" disabled={busy !== null} onClick={() => feedback(item.id, 'REJECTED')}>Non pertinente</button>
                  <button className="button button--secondary" type="button" disabled={busy !== null} onClick={() => feedback(item.id, 'HIDDEN')}>Masquer</button>
                </div>
              )}
              <p className="disclaimer">{item.disclaimer}</p>
            </article>
          ))}
        </details>
      )}
    </section>
  )
}
