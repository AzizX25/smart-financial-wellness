import { useEffect, useMemo, useState } from 'react'
import { Card, formatScale4Display, InlineNotice, MetricCard, PageHeader } from '../components/Ui'
import { ApiError } from '../services/apiClient'
import { getDashboardSummary } from '../services/financialApi'
import {
  getSalaryPlan,
  getSalaryPlanProposals,
  listSalaryPlans,
  saveSalaryPlan,
} from '../services/salaryPlanningApi'

const DEFAULT_CURRENCY = 'TND'
const HISTORY_PAGE_SIZE = 12
const CURRENCIES = ['TND', 'EUR', 'USD']
let incomeSequence = 0

function currentMonth() {
  const today = new Date()
  return `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}`
}

function localIncome(label = '', expectedAmount = '') {
  incomeSequence += 1
  return { localId: `income-${incomeSequence}`, label, expectedAmount: String(expectedAmount ?? '') }
}

function savedDraft(plan) {
  return {
    persisted: true,
    incomes: plan.incomes.map((income) => localIncome(income.label, income.expectedAmount)),
    allocations: plan.allocations.map((allocation) => ({
      ...allocation,
      confirmedAmount: allocation.confirmedAmount === null ? '' : String(allocation.confirmedAmount),
    })),
    totals: {
      totalIncome: plan.totalIncome,
      totalExpenses: plan.totalExpenses,
      savingCapacity: plan.savingCapacity,
      deficit: plan.deficit,
    },
    recommendationMetadata: plan.recommendationMetadata,
  }
}

function proposalDraft(proposal) {
  return {
    persisted: false,
    incomes: [],
    allocations: proposal.allocations.map((allocation) => ({
      ...allocation,
      confirmedAmount: '',
      effectiveAmount: allocation.recommendedAmount,
    })),
    totals: null,
    recommendationMetadata: proposal.recommendationMetadata,
  }
}

function editableSnapshot(draft) {
  if (!draft) return ''
  return JSON.stringify({
    incomes: draft.incomes.map(({ label, expectedAmount }) => ({ label, expectedAmount })),
    overrides: draft.allocations.map(({ categoryId, confirmedAmount }) => ({ categoryId, confirmedAmount })),
  })
}

function normalizeDecimal(value) {
  return String(value ?? '').trim().replace(',', '.')
}

function parseScale4(value) {
  const match = normalizeDecimal(value).match(/^(-?)(\d+)(?:\.(\d{0,4}))?$/)
  if (!match) return null
  const fraction = (match[3] || '').padEnd(4, '0')
  const units = (BigInt(match[2]) * 10000n) + BigInt(fraction || '0')
  return match[1] ? -units : units
}

function formatScale4(units, currency) {
  if (units === null || units === undefined) return 'À corriger'
  return formatScale4Display(units, currency)
}

function validateAmount(value, optional = false) {
  const normalized = normalizeDecimal(value)
  if (!normalized) return optional ? '' : 'Indiquez un montant.'
  if (normalized.startsWith('-')) return 'Le montant doit être positif ou nul.'
  if (!/^\d+(?:\.\d+)?$/.test(normalized)) return 'Saisissez un montant valide.'
  const [integer, fraction = ''] = normalized.split('.')
  if (integer.length > 15) return 'Le montant est trop élevé.'
  if (fraction.length > 4) return 'Utilisez au maximum quatre chiffres après la virgule.'
  return ''
}

function estimateDraft(draft) {
  if (!draft) return null
  let totalIncome = 0n
  for (const income of draft.incomes) {
    const amount = parseScale4(income.expectedAmount)
    if (amount === null || amount < 0n) return null
    totalIncome += amount
  }
  let totalExpenses = 0n
  for (const allocation of draft.allocations) {
    const selected = normalizeDecimal(allocation.confirmedAmount)
      ? allocation.confirmedAmount
      : allocation.recommendedAmount
    const amount = parseScale4(selected)
    if (amount === null || amount < 0n) return null
    totalExpenses += amount
  }
  const savingCapacity = totalIncome - totalExpenses
  return {
    totalIncome,
    totalExpenses,
    savingCapacity,
    deficit: savingCapacity < 0n ? -savingCapacity : 0n,
  }
}

function formatMonth(month) {
  if (!month) return ''
  const [year, monthNumber] = month.split('-').map(Number)
  return new Intl.DateTimeFormat('fr-FR', { month: 'long', year: 'numeric' })
    .format(new Date(year, monthNumber - 1, 1))
}

function formatDate(date) {
  if (!date) return ''
  return new Intl.DateTimeFormat('fr-FR', { day: 'numeric', month: 'long', year: 'numeric' })
    .format(new Date(`${date}T00:00:00`))
}

function friendlyApiError(error, action) {
  if (error instanceof ApiError && error.status === 0) {
    return 'Impossible de joindre le service. Vérifiez votre connexion puis réessayez.'
  }
  if (error instanceof ApiError && error.status === 400) {
    return 'Certaines informations doivent être corrigées avant de continuer.'
  }
  return action === 'save'
    ? 'Le plan n’a pas pu être enregistré. Vos modifications restent affichées.'
    : 'Le plan n’a pas pu être chargé pour le moment.'
}

export default function SalaryPlanningPage() {
  const [month, setMonth] = useState(currentMonth)
  const [currency, setCurrency] = useState(DEFAULT_CURRENCY)
  const [draft, setDraft] = useState(null)
  const [expandedIncomeId, setExpandedIncomeId] = useState('')
  const [baseline, setBaseline] = useState('')
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [loadError, setLoadError] = useState('')
  const [saveError, setSaveError] = useState('')
  const [success, setSuccess] = useState('')
  const [fieldErrors, setFieldErrors] = useState({})
  const [reloadToken, setReloadToken] = useState(0)
  const [history, setHistory] = useState({ items: [], page: 0, size: HISTORY_PAGE_SIZE, totalElements: 0, totalPages: 0 })
  const [historyPage, setHistoryPage] = useState(0)
  const [historyLoading, setHistoryLoading] = useState(true)
  const [historyError, setHistoryError] = useState('')
  const [historyToken, setHistoryToken] = useState(0)
  const [actualSpending, setActualSpending] = useState(null)
  const [actualSpendingLoading, setActualSpendingLoading] = useState(true)
  const [actualSpendingError, setActualSpendingError] = useState('')
  const [actualSpendingToken, setActualSpendingToken] = useState(0)

  const dirty = Boolean(draft) && editableSnapshot(draft) !== baseline
  const estimate = useMemo(() => estimateDraft(draft), [draft])
  const useEstimate = Boolean(draft) && (!draft.persisted || dirty)
  const displayedTotals = useEstimate
    ? estimate
    : draft?.totals && {
      totalIncome: parseScale4(draft.totals.totalIncome),
      totalExpenses: parseScale4(draft.totals.totalExpenses),
      savingCapacity: parseScale4(draft.totals.savingCapacity),
      deficit: parseScale4(draft.totals.deficit),
    }

  useEffect(() => {
    let active = true
    setLoading(true)
    setLoadError('')
    setSaveError('')
    setSuccess('')
    setFieldErrors({})

    async function loadSelection() {
      try {
        const plan = await getSalaryPlan(month, currency)
        if (!active) return
        const nextDraft = savedDraft(plan)
        setDraft(nextDraft)
        setBaseline(editableSnapshot(nextDraft))
      } catch (error) {
        if (!active) return
        if (error instanceof ApiError && error.status === 404) {
          try {
            const proposal = await getSalaryPlanProposals(month, currency)
            if (!active) return
            const nextDraft = proposalDraft(proposal)
            setDraft(nextDraft)
            setBaseline(editableSnapshot(nextDraft))
          } catch (proposalError) {
            if (!active) return
            setDraft(null)
            setBaseline('')
            setLoadError(friendlyApiError(proposalError, 'load'))
          }
        } else {
          setDraft(null)
          setBaseline('')
          setLoadError(friendlyApiError(error, 'load'))
        }
      } finally {
        if (active) setLoading(false)
      }
    }

    loadSelection()
    return () => { active = false }
  }, [currency, month, reloadToken])

  useEffect(() => {
    let active = true
    setActualSpending(null)
    setActualSpendingLoading(true)
    setActualSpendingError('')
    getDashboardSummary(month)
      .then((summary) => {
        if (!active) return
        if (!Array.isArray(summary.categorySpending)) {
          throw new Error('Category spending is unavailable')
        }
        setActualSpending(summary.categorySpending.find((item) => item.currency === currency) || {
          currency,
          totalExpenses: '0.0000',
          categories: [],
          uncategorizedCount: 0,
          uncategorizedAmount: '0.0000',
        })
      })
      .catch(() => {
        if (active) setActualSpendingError('Les dépenses réelles n’ont pas pu être chargées.')
      })
      .finally(() => {
        if (active) setActualSpendingLoading(false)
      })
    return () => { active = false }
  }, [actualSpendingToken, currency, month])

  useEffect(() => {
    let active = true
    setHistoryLoading(true)
    setHistoryError('')
    listSalaryPlans(currency, historyPage, HISTORY_PAGE_SIZE)
      .then((result) => {
        if (active) setHistory(result)
      })
      .catch((error) => {
        if (active) setHistoryError(friendlyApiError(error, 'load'))
      })
      .finally(() => {
        if (active) setHistoryLoading(false)
      })
    return () => { active = false }
  }, [currency, historyPage, historyToken])

  function selectPeriod(nextMonth, nextCurrency = currency) {
    if (!nextMonth || saving) return
    if (dirty && !window.confirm('Abandonner les modifications non enregistrées de ce plan ?')) {
      return
    }
    setDraft(null)
    setBaseline('')
    setFieldErrors({})
    setSaveError('')
    setSuccess('')
    if (nextCurrency !== currency) setHistoryPage(0)
    setMonth(nextMonth)
    setCurrency(nextCurrency)
  }

  function updateIncome(localId, field, value) {
    setDraft((current) => ({
      ...current,
      incomes: current.incomes.map((income) => income.localId === localId ? { ...income, [field]: value } : income),
    }))
    setFieldErrors((current) => ({ ...current, [`${localId}-${field}`]: '' }))
    setSuccess('')
  }

  function addIncome(label = '') {
    const income = localIncome(label)
    setExpandedIncomeId(income.localId)
    setDraft((current) => ({ ...current, incomes: [...current.incomes, income] }))
    setSuccess('')
  }

  function removeIncome(localId) {
    setDraft((current) => ({ ...current, incomes: current.incomes.filter((income) => income.localId !== localId) }))
    setSuccess('')
  }

  function updateOverride(categoryId, value) {
    setDraft((current) => ({
      ...current,
      allocations: current.allocations.map((allocation) => (
        allocation.categoryId === categoryId ? { ...allocation, confirmedAmount: value } : allocation
      )),
    }))
    setFieldErrors((current) => ({ ...current, [`allocation-${categoryId}`]: '' }))
    setSuccess('')
  }

  function validateDraft() {
    const errors = {}
    draft.incomes.forEach((income) => {
      if (!income.label.trim()) errors[`${income.localId}-label`] = 'Donnez un nom à cette source de revenu.'
      else if (income.label.trim().length > 160) errors[`${income.localId}-label`] = 'Le libellé ne peut pas dépasser 160 caractères.'
      const amountError = validateAmount(income.expectedAmount)
      if (amountError) errors[`${income.localId}-expectedAmount`] = amountError
    })
    draft.allocations.forEach((allocation) => {
      const amountError = validateAmount(allocation.confirmedAmount, true)
      if (amountError) errors[`allocation-${allocation.categoryId}`] = amountError
    })
    setFieldErrors(errors)
    return Object.keys(errors).length === 0
  }

  function applyBackendFieldErrors(error) {
    if (!(error instanceof ApiError)) return
    const translated = {}
    Object.keys(error.fieldErrors || {}).forEach((path) => {
      const incomeMatch = path.match(/incomes\[(\d+)]\.(label|expectedAmount)/)
      const allocationMatch = path.match(/allocationOverrides\[(\d+)]\.confirmedAmount/)
      if (incomeMatch && draft.incomes[Number(incomeMatch[1])]) {
        const income = draft.incomes[Number(incomeMatch[1])]
        translated[`${income.localId}-${incomeMatch[2]}`] = incomeMatch[2] === 'label'
          ? 'Vérifiez le libellé de ce revenu.'
          : 'Vérifiez ce montant.'
      }
      if (allocationMatch) {
        const allocation = draft.allocations.filter((item) => normalizeDecimal(item.confirmedAmount))[Number(allocationMatch[1])]
        if (allocation) translated[`allocation-${allocation.categoryId}`] = 'Vérifiez ce montant.'
      }
    })
    setFieldErrors((current) => ({ ...current, ...translated }))
  }

  async function handleSave(event) {
    event.preventDefault()
    setSaveError('')
    setSuccess('')
    if (!validateDraft()) {
      setSaveError('Corrigez les champs indiqués avant d’enregistrer le plan.')
      return
    }

    const payload = {
      incomes: draft.incomes.map((income) => ({
        label: income.label.trim(),
        expectedAmount: Number(normalizeDecimal(income.expectedAmount)),
      })),
      allocationOverrides: draft.allocations
        .filter((allocation) => normalizeDecimal(allocation.confirmedAmount))
        .map((allocation) => ({
          categoryId: allocation.categoryId,
          confirmedAmount: Number(normalizeDecimal(allocation.confirmedAmount)),
        })),
    }

    setSaving(true)
    try {
      const saved = await saveSalaryPlan(month, currency, payload)
      const nextDraft = savedDraft(saved)
      setDraft(nextDraft)
      setBaseline(editableSnapshot(nextDraft))
      setFieldErrors({})
      setSuccess('Votre plan mensuel a bien été enregistré.')
      setHistoryToken((value) => value + 1)
    } catch (error) {
      applyBackendFieldErrors(error)
      setSaveError(friendlyApiError(error, 'save'))
    } finally {
      setSaving(false)
    }
  }

  const metadata = draft?.recommendationMetadata
  const noHistory = metadata?.metadataAvailable && metadata.usableMonthCount === 0

  return (
    <div className="page salary-planning-page">
      <PageHeader
        eyebrow={formatMonth(month)}
        title="Planifier mon salaire"
        description="Préparez vos revenus et vos dépenses du mois, avec des recommandations claires fondées sur votre historique."
      />

      <Card className="salary-plan-toolbar" aria-label="Période du plan">
        <label className="field">
          <span>Mois</span>
          <input disabled={saving} onChange={(event) => selectPeriod(event.target.value)} type="month" value={month} />
        </label>
        <label className="field">
          <span>Devise</span>
          <select disabled={saving} onChange={(event) => selectPeriod(month, event.target.value)} value={currency}>
            {CURRENCIES.map((item) => <option key={item} value={item}>{item}</option>)}
          </select>
        </label>
        <p>Chaque devise reste indépendante et n’est jamais convertie.</p>
      </Card>

      {loadError && (
        <InlineNotice live tone="warning" icon="alert">
          <p>{loadError}</p>
          <button className="button button--secondary" onClick={() => setReloadToken((value) => value + 1)} type="button">Réessayer</button>
        </InlineNotice>
      )}
      {saveError && <InlineNotice live tone="warning" icon="alert">{saveError}</InlineNotice>}
      {success && <InlineNotice live tone="success" icon="check">{success}</InlineNotice>}

      <Card className="salary-editor-section" as="section" aria-labelledby="actual-spending-title">
        <div className="section-heading">
          <div><p className="eyebrow">Transactions comptabilisées</p><h2 id="actual-spending-title">Dépenses réelles par catégorie</h2></div>
          {actualSpending && <div><span>Total réel</span><strong>{formatScale4(parseScale4(actualSpending.totalExpenses), currency)}</strong></div>}
        </div>
        {actualSpendingError ? (
          <InlineNotice live tone="warning" icon="alert">
            <p>{actualSpendingError}</p>
            <button className="button button--secondary" onClick={() => setActualSpendingToken((value) => value + 1)} type="button">Réessayer</button>
          </InlineNotice>
        ) : actualSpendingLoading ? (
          <p className="muted" aria-live="polite">Chargement des dépenses réelles…</p>
        ) : actualSpending && actualSpending.categories.length === 0 && actualSpending.uncategorizedCount === 0 ? (
          <div className="salary-empty-state"><p>Aucune dépense comptabilisée pour ce mois et cette devise.</p></div>
        ) : actualSpending ? (
          <div className="category-total-list">
            {actualSpending.categories.map((item) => (
              <div key={item.categoryId}>
                <span><strong>{item.categoryName}</strong><small>{item.transactionCount} transaction{item.transactionCount === 1 ? '' : 's'}</small></span>
                <strong>{formatScale4(parseScale4(item.totalAmount), currency)}</strong>
              </div>
            ))}
            {actualSpending.uncategorizedCount > 0 && (
              <div>
                <span><strong>Non catégorisé</strong><small>{actualSpending.uncategorizedCount} transaction{actualSpending.uncategorizedCount === 1 ? '' : 's'}</small></span>
                <strong>{formatScale4(parseScale4(actualSpending.uncategorizedAmount), currency)}</strong>
              </div>
            )}
          </div>
        ) : null}
      </Card>

      {loading ? (
        <Card className="salary-plan-state" aria-live="polite"><span className="auth-spinner" /> Chargement de votre plan…</Card>
      ) : draft ? (
        <form aria-busy={saving} className="salary-plan-form" onSubmit={handleSave}>
          {!draft.persisted && (
            <InlineNotice tone="info" icon="plan">
              Ce plan n’est pas encore enregistré. Vérifiez les propositions puis ajoutez vos revenus du mois.
            </InlineNotice>
          )}
          {noHistory && (
            <InlineNotice tone="warning" icon="info">
              Nous n’avons pas encore assez d’historique pour proposer des montants personnalisés. Les recommandations commencent donc à zéro et restent entièrement ajustables.
            </InlineNotice>
          )}
          {metadata && !metadata.metadataAvailable && (
            <InlineNotice tone="info" icon="info">
              Les détails de cette recommandation ne sont pas disponibles pour cet ancien plan.
            </InlineNotice>
          )}

          <section className="metrics-grid metrics-grid--planning" aria-label={useEstimate ? 'Estimation du plan non enregistré' : 'Résultats enregistrés du plan'}>
            <MetricCard icon="wallet" label="Total des revenus prévus" value={formatScale4(displayedTotals?.totalIncome, currency)} note={useEstimate ? 'Estimation avant enregistrement' : 'Calculé par le serveur'} tone="teal" />
            <MetricCard icon="transactions" label="Total des dépenses planifiées" value={formatScale4(displayedTotals?.totalExpenses, currency)} note={useEstimate ? 'Estimation avant enregistrement' : 'Calculé par le serveur'} tone="peach" />
            <MetricCard icon="target" label="Capacité d’épargne mensuelle" value={formatScale4(displayedTotals?.savingCapacity, currency)} note={useEstimate ? 'Estimation avant enregistrement' : 'Calculée par le serveur'} tone="mint" />
            <MetricCard icon="alert" label="Déficit" value={formatScale4(displayedTotals?.deficit, currency)} note={displayedTotals?.deficit > 0n ? 'Les dépenses dépassent les revenus' : 'Aucun déficit prévu'} tone="gold" />
          </section>

          {displayedTotals?.savingCapacity < 0n && (
            <InlineNotice live tone="warning" icon="alert">
              Votre plan présente un déficit de <strong>{formatScale4(displayedTotals.deficit, currency)}</strong>. Réduisez une allocation ou augmentez les revenus prévus avant de vous engager.
            </InlineNotice>
          )}

          <Card className="salary-editor-section" as="section" aria-labelledby="income-editor-title">
            <div className="section-heading">
              <h2 id="income-editor-title">Revenus prévus</h2>
              <button className="button button--secondary" disabled={saving} onClick={() => addIncome()} type="button">Ajouter un revenu</button>
            </div>
            <div className="income-shortcuts" aria-label="Ajouter une source de revenu">
              {['Salaire', 'Prime', 'Autre revenu'].map((label) => <button className="button button--secondary" disabled={saving} key={label} onClick={() => addIncome(label)} type="button">+ {label}</button>)}
            </div>
            {draft.incomes.length === 0 ? (
              <div className="salary-empty-state"><p>Aucun revenu prévu pour ce mois.</p><button className="button button--secondary" onClick={() => addIncome()} type="button">Ajouter ma première source</button></div>
            ) : (
              <div className="income-editor-list">
                {draft.incomes.map((income, index) => (
                  <div className="income-source" key={income.localId}>
                    <button className="income-source__toggle" aria-label={`Modifier le revenu ${index + 1}`} aria-controls={`${income.localId}-editor`} aria-expanded={expandedIncomeId === income.localId || Boolean(fieldErrors[`${income.localId}-label`] || fieldErrors[`${income.localId}-expectedAmount`])} disabled={saving} onClick={() => setExpandedIncomeId((current) => current === income.localId ? '' : income.localId)} type="button">
                      <span>{income.label || 'Nouveau revenu'}</span><strong>{formatScale4(parseScale4(income.expectedAmount), currency)}</strong><span>Modifier</span>
                    </button>
                    <div className="income-editor-row" id={`${income.localId}-editor`} hidden={expandedIncomeId !== income.localId && !fieldErrors[`${income.localId}-label`] && !fieldErrors[`${income.localId}-expectedAmount`]}>
                    <label className="field">
                      <span>Libellé du revenu {index + 1}</span>
                      <input
                        aria-describedby={fieldErrors[`${income.localId}-label`] ? `${income.localId}-label-error` : undefined}
                        aria-invalid={Boolean(fieldErrors[`${income.localId}-label`])}
                        disabled={saving}
                        maxLength={160}
                        onChange={(event) => updateIncome(income.localId, 'label', event.target.value)}
                        value={income.label}
                      />
                      {fieldErrors[`${income.localId}-label`] && <small className="field-error" id={`${income.localId}-label-error`}>{fieldErrors[`${income.localId}-label`]}</small>}
                    </label>
                    <label className="field">
                      <span>Montant du revenu {index + 1} ({currency})</span>
                      <input
                        aria-describedby={fieldErrors[`${income.localId}-expectedAmount`] ? `${income.localId}-amount-error` : undefined}
                        aria-invalid={Boolean(fieldErrors[`${income.localId}-expectedAmount`])}
                        disabled={saving}
                        inputMode="decimal"
                        onChange={(event) => updateIncome(income.localId, 'expectedAmount', event.target.value)}
                        placeholder="0,0000"
                        value={income.expectedAmount}
                      />
                      {fieldErrors[`${income.localId}-expectedAmount`] && <small className="field-error" id={`${income.localId}-amount-error`}>{fieldErrors[`${income.localId}-expectedAmount`]}</small>}
                    </label>
                    <button className="button button--danger-quiet" disabled={saving} onClick={() => removeIncome(income.localId)} type="button">Supprimer</button>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </Card>

          <Card className="salary-editor-section" as="section" aria-labelledby="allocation-editor-title">
            <div className="section-heading">
              <div><p className="eyebrow">Repères personnalisés</p><h2 id="allocation-editor-title">Budget prévu par catégorie</h2></div>
            </div>
            <div className="allocation-editor-list">
              {draft.allocations.map((allocation) => {
                const confirmed = normalizeDecimal(allocation.confirmedAmount)
                const effective = confirmed ? parseScale4(allocation.confirmedAmount) : parseScale4(allocation.recommendedAmount)
                return (
                  <article className="allocation-editor-row" key={allocation.categoryId}>
                    <div className="allocation-editor-row__category"><h3>{allocation.categoryName}</h3><p>Recommandé : <strong>{formatScale4(parseScale4(allocation.recommendedAmount), currency)}</strong></p></div>
                    <label className="field">
                      <span>Montant confirmé pour {allocation.categoryName} ({currency})</span>
                      <input
                        aria-describedby={fieldErrors[`allocation-${allocation.categoryId}`] ? `allocation-${allocation.categoryId}-error` : undefined}
                        aria-invalid={Boolean(fieldErrors[`allocation-${allocation.categoryId}`])}
                        disabled={saving}
                        inputMode="decimal"
                        onChange={(event) => updateOverride(allocation.categoryId, event.target.value)}
                        placeholder="Utiliser la recommandation"
                        value={allocation.confirmedAmount}
                      />
                      {fieldErrors[`allocation-${allocation.categoryId}`] && <small className="field-error" id={`allocation-${allocation.categoryId}-error`}>{fieldErrors[`allocation-${allocation.categoryId}`]}</small>}
                    </label>
                    <div className="allocation-editor-row__effective">
                      <span>{confirmed ? 'Ajusté manuellement' : 'Recommandation utilisée'}</span>
                      <strong>{formatScale4(effective, currency)}</strong>
                      {confirmed && <button className="button button--ghost" disabled={saving} onClick={() => updateOverride(allocation.categoryId, '')} type="button">Utiliser la recommandation</button>}
                    </div>
                  </article>
                )
              })}
            </div>
          </Card>

          {metadata?.metadataAvailable && (
            <Card className="recommendation-explanation" as="section" aria-labelledby="recommendation-explanation-title">
              <p className="eyebrow">Pourquoi ces montants ?</p>
              <h2 id="recommendation-explanation-title">Une recommandation déterministe et vérifiable</h2>
              <p>Période examinée : du {formatDate(metadata.periodStart)} au {formatDate(metadata.periodEndInclusive)}.</p>
              <p>{metadata.usableMonthCount === 0 ? 'Aucun mois exploitable.' : `${metadata.usableMonthCount} mois historique${metadata.usableMonthCount > 1 ? 's' : ''} exploitable${metadata.usableMonthCount > 1 ? 's' : ''}.`}</p>
            </Card>
          )}

          <div className="salary-plan-actions">
            <p>{dirty ? 'Modifications non enregistrées.' : draft.persisted ? 'Plan à jour.' : 'Plan prêt à être créé.'}</p>
            <button className="button button--primary" disabled={saving || !dirty && draft.persisted} type="submit">
              {saving ? 'Enregistrement…' : 'Enregistrer le plan complet'}
            </button>
          </div>
        </form>
      ) : null}

      <Card className="salary-plan-history" as="section" aria-labelledby="salary-history-title">
        <div className="section-heading"><div><p className="eyebrow">Historique</p><h2 id="salary-history-title">Plans précédents en {currency}</h2></div></div>
        {historyError && <InlineNotice live tone="warning" icon="alert">{historyError}</InlineNotice>}
        {historyLoading ? <p className="muted" aria-live="polite">Chargement des plans précédents…</p>
          : history.items.length === 0 ? <div className="salary-empty-state"><p>Aucun plan précédent dans cette devise.</p></div>
            : <div className="salary-history-list">{history.items.map((plan) => (
              <button className={plan.month === month ? 'is-current' : ''} key={`${plan.month}-${plan.currency}`} onClick={() => selectPeriod(plan.month)} type="button">
                <span><strong>{formatMonth(plan.month)}</strong><small>{plan.currency}</small></span>
                <span>{formatScale4(parseScale4(plan.savingCapacity), plan.currency)}<small>capacité d’épargne</small></span>
              </button>
            ))}</div>}
        {history.totalPages > 1 && (
          <nav aria-label="Pagination des plans précédents" className="pagination-controls">
            <button className="button button--secondary" disabled={historyLoading || history.page === 0} onClick={() => setHistoryPage((value) => Math.max(0, value - 1))} type="button">Précédente</button>
            <span>Page {history.page + 1} sur {history.totalPages}</span>
            <button className="button button--secondary" disabled={historyLoading || history.page + 1 >= history.totalPages} onClick={() => setHistoryPage((value) => value + 1)} type="button">Suivante</button>
          </nav>
        )}
      </Card>
    </div>
  )
}
