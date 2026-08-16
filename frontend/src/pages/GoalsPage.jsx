import { useEffect, useMemo, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { Badge, Card, InlineNotice, PageHeader, ProgressBar } from '../components/Ui'
import { ApiError } from '../services/apiClient'
import {
  addGoalContribution,
  archiveGoal,
  changeGoalStatus,
  createGoal,
  deleteGoalContribution,
  getGoalAllocations,
  listGoalContributions,
  listGoals,
  replaceGoalAllocations,
  simulateGoal,
  updateGoal,
} from '../services/goalsApi'

const DEFAULT_CURRENCY = 'TND'
const CURRENCIES = ['TND', 'EUR', 'USD']
const GOAL_TYPES = [
  ['EMERGENCY_FUND', 'Fonds d’urgence'],
  ['PURCHASE', 'Achat important'],
  ['TRAVEL', 'Voyage'],
  ['EDUCATION', 'Études'],
  ['DEBT_REPAYMENT', 'Remboursement de dette'],
  ['RETIREMENT', 'Retraite'],
  ['OTHER', 'Autre projet'],
]
const PRIORITIES = [
  ['LOW', 'Faible'],
  ['MEDIUM', 'Moyenne'],
  ['HIGH', 'Haute'],
  ['CRITICAL', 'Essentielle'],
]
const TYPE_LABELS = Object.fromEntries(GOAL_TYPES)
const PRIORITY_LABELS = Object.fromEntries(PRIORITIES)
const STATUS_LABELS = {
  ACTIVE: 'Actif',
  PAUSED: 'En pause',
  ACHIEVED: 'Atteint',
  CANCELLED: 'Annulé',
  ARCHIVED: 'Archivé',
}
const STATUS_TONES = {
  ACTIVE: 'teal',
  PAUSED: 'gold',
  ACHIEVED: 'mint',
  CANCELLED: 'rose',
  ARCHIVED: 'neutral',
}

function currentMonth() {
  const today = new Date()
  return `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}`
}

function todayIso() {
  const today = new Date()
  return `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}-${String(today.getDate()).padStart(2, '0')}`
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
  if (units === null || units === undefined) return 'Montant à corriger'
  const negative = units < 0n
  const absolute = negative ? -units : units
  const integer = (absolute / 10000n).toLocaleString('fr-FR')
  const minimumFractionDigits = currency === 'TND' ? 3 : 2
  let fraction = String(absolute % 10000n).padStart(4, '0')
  while (fraction.length > minimumFractionDigits && fraction.endsWith('0')) fraction = fraction.slice(0, -1)
  return `${negative ? '-' : ''}${integer},${fraction} ${currency}`
}

function formatMoney(value, currency) {
  if (value === null || value === undefined) return '—'
  return new Intl.NumberFormat('fr-TN', {
    style: 'currency',
    currency,
    minimumFractionDigits: currency === 'TND' ? 3 : 2,
    maximumFractionDigits: 4,
  }).format(Number(value))
}

function formatMonth(month) {
  const [year, monthNumber] = month.split('-').map(Number)
  return new Intl.DateTimeFormat('fr-FR', { month: 'long', year: 'numeric' })
    .format(new Date(year, monthNumber - 1, 1))
}

function formatDate(date) {
  if (!date) return 'Aucune date définie'
  return new Intl.DateTimeFormat('fr-FR', { day: 'numeric', month: 'long', year: 'numeric' })
    .format(new Date(`${date}T00:00:00`))
}

function validateAmount(value, { optional = false, strictlyPositive = false } = {}) {
  const normalized = normalizeDecimal(value)
  if (!normalized) return optional ? '' : 'Indiquez un montant.'
  if (!/^\d+(?:\.\d+)?$/.test(normalized)) return 'Saisissez un montant positif valide.'
  const [integer, fraction = ''] = normalized.split('.')
  if (integer.length > 15) return 'Le montant est trop élevé.'
  if (fraction.length > 4) return 'Utilisez au maximum quatre chiffres après la virgule.'
  if (strictlyPositive && Number(normalized) <= 0) return 'Le montant doit être strictement positif.'
  return ''
}

function friendlyError(error, subject, action = 'load') {
  if (error instanceof ApiError && error.status === 0) {
    return 'Impossible de joindre le service. Vérifiez votre connexion puis réessayez.'
  }
  if (error instanceof ApiError && error.status === 400) {
    return 'Certaines informations doivent être corrigées avant de continuer.'
  }
  if (error instanceof ApiError && error.status === 409) {
    return error.code === 'GOAL_ALLOCATION_CAPACITY_EXCEEDED'
      ? 'La répartition dépasse la capacité disponible. Actualisez les montants puis réessayez.'
      : 'Cette opération n’est plus possible dans la situation actuelle.'
  }
  if (action === 'save') return `${subject} n’a pas pu être enregistré. Vos saisies restent affichées.`
  return `${subject} n’a pas pu être chargé pour le moment.`
}

function emptyGoalForm(currency) {
  return {
    name: '',
    description: '',
    type: 'OTHER',
    currency,
    targetAmount: '',
    initialAmount: '0',
    targetDate: '',
    priority: 'MEDIUM',
  }
}

function goalFormFrom(goal) {
  return {
    name: goal.name,
    description: goal.description || '',
    type: goal.type,
    currency: goal.currency,
    targetAmount: String(goal.targetAmount),
    initialAmount: String(goal.initialAmount),
    targetDate: goal.targetDate || '',
    priority: goal.priority,
  }
}

function allocationDraft(overview) {
  const draft = {}
  overview?.allocations?.forEach((allocation) => {
    if (allocation.allocationStatus === 'CONFIRMED' && allocation.goalStatus === 'ACTIVE') {
      draft[allocation.goalId] = String(allocation.allocatedAmount)
    }
  })
  return draft
}

function allocationSnapshot(values) {
  return JSON.stringify(Object.entries(values)
    .filter(([, value]) => normalizeDecimal(value) && parseScale4(value) !== 0n)
    .sort(([left], [right]) => left.localeCompare(right)))
}

export default function GoalsPage() {
  const [month, setMonth] = useState(currentMonth)
  const [currency, setCurrency] = useState(DEFAULT_CURRENCY)
  const [includeArchived, setIncludeArchived] = useState(false)
  const [goals, setGoals] = useState([])
  const [goalsLoading, setGoalsLoading] = useState(true)
  const [goalsError, setGoalsError] = useState('')
  const [goalReloadToken, setGoalReloadToken] = useState(0)
  const [allocation, setAllocation] = useState(null)
  const [allocationLoading, setAllocationLoading] = useState(true)
  const [allocationError, setAllocationError] = useState('')
  const [allocationReloadToken, setAllocationReloadToken] = useState(0)
  const [allocationValues, setAllocationValues] = useState({})
  const [allocationBaseline, setAllocationBaseline] = useState('[]')
  const [allocationFieldErrors, setAllocationFieldErrors] = useState({})
  const [allocationSaving, setAllocationSaving] = useState(false)
  const [allocationSuccess, setAllocationSuccess] = useState('')
  const [formMode, setFormMode] = useState(null)
  const [editingGoal, setEditingGoal] = useState(null)
  const [goalForm, setGoalForm] = useState(emptyGoalForm(DEFAULT_CURRENCY))
  const [goalFormErrors, setGoalFormErrors] = useState({})
  const [goalFormError, setGoalFormError] = useState('')
  const [goalSaving, setGoalSaving] = useState(false)
  const [goalSuccess, setGoalSuccess] = useState('')
  const [goalActionBusy, setGoalActionBusy] = useState('')
  const [expandedGoalId, setExpandedGoalId] = useState(null)
  const [contributions, setContributions] = useState([])
  const [contributionsLoading, setContributionsLoading] = useState(false)
  const [contributionError, setContributionError] = useState('')
  const [contributionSuccess, setContributionSuccess] = useState('')
  const [contributionForm, setContributionForm] = useState({ amount: '', contributionDate: '' })
  const [contributionFieldError, setContributionFieldError] = useState('')
  const [contributionSaving, setContributionSaving] = useState(false)
  const [simulationAmount, setSimulationAmount] = useState('')
  const [simulation, setSimulation] = useState(null)
  const [simulationError, setSimulationError] = useState('')
  const [simulationLoading, setSimulationLoading] = useState(false)
  const contributionRequestRef = useRef(0)

  const allocationDirty = allocationSnapshot(allocationValues) !== allocationBaseline
  const activeGoals = goals.filter((goal) => goal.status === 'ACTIVE')
  const selectedGoal = goals.find((goal) => goal.id === expandedGoalId) || null
  const pastMonth = month < currentMonth()

  const allocationEstimate = useMemo(() => {
    let total = 0n
    let valid = true
    Object.values(allocationValues).forEach((value) => {
      if (!normalizeDecimal(value)) return
      const amount = parseScale4(value)
      if (amount === null || amount < 0n) valid = false
      else total += amount
    })
    const capacity = parseScale4(allocation?.allocatableCapacity ?? 0) ?? 0n
    return { valid, total, remaining: capacity - total, overCapacity: valid && total > capacity }
  }, [allocation, allocationValues])

  useEffect(() => {
    let active = true
    setGoalsLoading(true)
    setGoalsError('')
    listGoals(currency, includeArchived)
      .then((items) => {
        if (active) setGoals(items)
      })
      .catch((error) => {
        if (active) {
          setGoals([])
          setGoalsError(friendlyError(error, 'La liste de vos objectifs'))
        }
      })
      .finally(() => {
        if (active) setGoalsLoading(false)
      })
    return () => { active = false }
  }, [currency, goalReloadToken, includeArchived])

  useEffect(() => {
    let active = true
    setAllocationLoading(true)
    setAllocationError('')
    setAllocationSuccess('')
    getGoalAllocations(month, currency)
      .then((overview) => {
        if (!active) return
        const draft = allocationDraft(overview)
        setAllocation(overview)
        setAllocationValues(draft)
        setAllocationBaseline(allocationSnapshot(draft))
        setAllocationFieldErrors({})
      })
      .catch((error) => {
        if (!active) return
        setAllocation(null)
        setAllocationValues({})
        setAllocationBaseline('[]')
        setAllocationError(friendlyError(error, 'La répartition mensuelle'))
      })
      .finally(() => {
        if (active) setAllocationLoading(false)
      })
    return () => { active = false }
  }, [allocationReloadToken, currency, month])

  function selectPeriod(nextMonth, nextCurrency = currency) {
    if (allocationSaving) return
    if (allocationDirty && !window.confirm('Abandonner la répartition non enregistrée ?')) return
    if (nextCurrency !== currency) {
      setGoals([])
      setExpandedGoalId(null)
      setFormMode(null)
      setCurrency(nextCurrency)
    }
    setAllocation(null)
    setAllocationValues({})
    setAllocationBaseline('[]')
    setAllocationFieldErrors({})
    setAllocationError('')
    setAllocationSuccess('')
    setMonth(nextMonth)
  }

  function openCreateForm() {
    setEditingGoal(null)
    setGoalForm(emptyGoalForm(currency))
    setGoalFormErrors({})
    setGoalFormError('')
    setFormMode('create')
  }

  function openEditForm(goal) {
    setEditingGoal(goal)
    setGoalForm(goalFormFrom(goal))
    setGoalFormErrors({})
    setGoalFormError('')
    setFormMode('edit')
  }

  function validateGoalForm() {
    const errors = {}
    const name = goalForm.name.trim()
    if (!name) errors.name = 'Donnez un nom à votre objectif.'
    else if (name.length > 160) errors.name = 'Le nom ne peut pas dépasser 160 caractères.'
    if (goalForm.description.trim().length > 1000) errors.description = 'La description est trop longue.'
    const targetError = validateAmount(goalForm.targetAmount, { strictlyPositive: true })
    if (targetError) errors.targetAmount = targetError
    const initialError = validateAmount(goalForm.initialAmount)
    if (initialError) errors.initialAmount = initialError
    const target = parseScale4(goalForm.targetAmount)
    const initial = parseScale4(goalForm.initialAmount)
    if (target !== null && initial !== null && initial > target) {
      errors.initialAmount = 'Le montant déjà épargné ne peut pas dépasser la cible.'
    }
    setGoalFormErrors(errors)
    return Object.keys(errors).length === 0
  }

  function updateGoalFormField(field, value) {
    setGoalForm((current) => ({ ...current, [field]: value }))
    setGoalFormErrors((current) => ({ ...current, [field]: '' }))
    setGoalFormError('')
  }

  async function submitGoal(event) {
    event.preventDefault()
    setGoalFormError('')
    setGoalSuccess('')
    if (!validateGoalForm()) {
      setGoalFormError('Corrigez les champs indiqués avant de continuer.')
      return
    }
    const payload = {
      name: goalForm.name.trim(),
      description: goalForm.description.trim() || null,
      type: goalForm.type,
      currency: goalForm.currency,
      targetAmount: Number(normalizeDecimal(goalForm.targetAmount)),
      initialAmount: Number(normalizeDecimal(goalForm.initialAmount)),
      targetDate: goalForm.targetDate || null,
      priority: goalForm.priority,
    }
    setGoalSaving(true)
    try {
      const saved = formMode === 'edit'
        ? await updateGoal(editingGoal.id, payload)
        : await createGoal(payload)
      setGoals((items) => formMode === 'edit'
        ? items.map((goal) => goal.id === saved.id ? saved : goal)
        : [...items, saved])
      setFormMode(null)
      setEditingGoal(null)
      setGoalSuccess(formMode === 'edit' ? 'Votre objectif a bien été mis à jour.' : 'Votre objectif a bien été créé.')
      setAllocationReloadToken((value) => value + 1)
    } catch (error) {
      setGoalFormError(friendlyError(error, 'L’objectif', 'save'))
    } finally {
      setGoalSaving(false)
    }
  }

  async function applyStatus(goal, status, confirmation) {
    if (!window.confirm(confirmation)) return
    setGoalActionBusy(`${goal.id}-${status}`)
    setGoalSuccess('')
    setGoalsError('')
    try {
      const updated = await changeGoalStatus(goal.id, status)
      setGoals((items) => items.map((item) => item.id === updated.id ? updated : item))
      setGoalSuccess('Le statut de votre objectif a bien été mis à jour.')
      setAllocationReloadToken((value) => value + 1)
    } catch (error) {
      setGoalsError(friendlyError(error, 'L’objectif', 'save'))
    } finally {
      setGoalActionBusy('')
    }
  }

  async function handleArchive(goal) {
    if (!window.confirm(`Archiver « ${goal.name} » ? Vous pourrez toujours l’afficher avec les objectifs archivés.`)) return
    setGoalActionBusy(`${goal.id}-archive`)
    setGoalsError('')
    try {
      await archiveGoal(goal.id)
      setGoals((items) => includeArchived
        ? items.map((item) => item.id === goal.id ? { ...item, status: 'ARCHIVED' } : item)
        : items.filter((item) => item.id !== goal.id))
      if (expandedGoalId === goal.id) setExpandedGoalId(null)
      setGoalSuccess('Votre objectif a été archivé.')
      setAllocationReloadToken((value) => value + 1)
    } catch (error) {
      setGoalsError(friendlyError(error, 'L’objectif', 'save'))
    } finally {
      setGoalActionBusy('')
    }
  }

  async function loadContributions(goalId) {
    const requestId = contributionRequestRef.current + 1
    contributionRequestRef.current = requestId
    setContributionsLoading(true)
    setContributionError('')
    try {
      const items = await listGoalContributions(goalId)
      if (contributionRequestRef.current === requestId) setContributions(items)
    } catch (error) {
      if (contributionRequestRef.current === requestId) {
        setContributions([])
        setContributionError(friendlyError(error, 'L’historique des versements'))
      }
    } finally {
      if (contributionRequestRef.current === requestId) setContributionsLoading(false)
    }
  }

  function toggleGoalDetails(goal) {
    if (expandedGoalId === goal.id) {
      contributionRequestRef.current += 1
      setExpandedGoalId(null)
      return
    }
    setExpandedGoalId(goal.id)
    setContributions([])
    setContributionForm({ amount: '', contributionDate: '' })
    setContributionFieldError('')
    setContributionSuccess('')
    setSimulationAmount('')
    setSimulation(null)
    setSimulationError('')
    loadContributions(goal.id)
  }

  async function submitContribution(event) {
    event.preventDefault()
    const amountError = validateAmount(contributionForm.amount, { strictlyPositive: true })
    if (amountError) {
      setContributionFieldError(amountError)
      return
    }
    if (contributionForm.contributionDate && contributionForm.contributionDate > todayIso()) {
      setContributionFieldError('La date du versement ne peut pas être dans le futur.')
      return
    }
    setContributionSaving(true)
    setContributionError('')
    setContributionSuccess('')
    try {
      const updated = await addGoalContribution(selectedGoal.id, {
        amount: Number(normalizeDecimal(contributionForm.amount)),
        ...(contributionForm.contributionDate ? { contributionDate: contributionForm.contributionDate } : {}),
      })
      setGoals((items) => items.map((goal) => goal.id === updated.id ? updated : goal))
      setContributionForm({ amount: '', contributionDate: '' })
      setContributionFieldError('')
      await loadContributions(selectedGoal.id)
      setContributionSuccess('Le versement a bien été ajouté et votre progression a été mise à jour.')
      setAllocationReloadToken((value) => value + 1)
    } catch (error) {
      setContributionError(friendlyError(error, 'Le versement', 'save'))
    } finally {
      setContributionSaving(false)
    }
  }

  async function removeContribution(contribution) {
    if (!window.confirm('Supprimer ce versement manuel ? La progression sera recalculée par le serveur.')) return
    setContributionSaving(true)
    setContributionError('')
    setContributionSuccess('')
    try {
      const updated = await deleteGoalContribution(selectedGoal.id, contribution.id)
      setGoals((items) => items.map((goal) => goal.id === updated.id ? updated : goal))
      await loadContributions(selectedGoal.id)
      setContributionSuccess('Le versement manuel a été supprimé et votre progression a été recalculée.')
      setAllocationReloadToken((value) => value + 1)
    } catch (error) {
      setContributionError(friendlyError(error, 'Le versement', 'save'))
    } finally {
      setContributionSaving(false)
    }
  }

  function updateAllocation(goalId, value) {
    setAllocationValues((current) => ({ ...current, [goalId]: value }))
    setAllocationFieldErrors((current) => ({
      ...current,
      [goalId]: normalizeDecimal(value) ? validateAmount(value) : '',
    }))
    setAllocationError('')
    setAllocationSuccess('')
  }

  async function saveAllocations(event) {
    event.preventDefault()
    const errors = {}
    const payload = []
    activeGoals.forEach((goal) => {
      const value = allocationValues[goal.id] ?? ''
      if (!normalizeDecimal(value)) return
      const error = validateAmount(value)
      if (error) errors[goal.id] = error
      else if (parseScale4(value) > 0n) payload.push({
        goalId: goal.id,
        amount: Number(normalizeDecimal(value)),
      })
    })
    setAllocationFieldErrors(errors)
    if (Object.keys(errors).length > 0) {
      setAllocationError('Corrigez les montants indiqués avant d’enregistrer la répartition.')
      return
    }
    if (allocationEstimate.overCapacity) {
      setAllocationError('La répartition estimée dépasse la capacité disponible.')
      return
    }
    if (payload.length === 0 && allocationBaseline !== '[]' && !window.confirm('Retirer toutes les allocations de ce mois ?')) return

    setAllocationSaving(true)
    setAllocationError('')
    setAllocationSuccess('')
    try {
      const saved = await replaceGoalAllocations(month, currency, payload)
      const draft = allocationDraft(saved)
      setAllocation(saved)
      setAllocationValues(draft)
      setAllocationBaseline(allocationSnapshot(draft))
      setAllocationFieldErrors({})
      setAllocationSuccess('Votre répartition mensuelle a bien été enregistrée.')
    } catch (error) {
      setAllocationError(friendlyError(error, 'La répartition mensuelle', 'save'))
    } finally {
      setAllocationSaving(false)
    }
  }

  async function submitSimulation(event) {
    event.preventDefault()
    const error = validateAmount(simulationAmount)
    if (error) {
      setSimulationError(error)
      return
    }
    setSimulationLoading(true)
    setSimulationError('')
    setSimulation(null)
    try {
      const result = await simulateGoal(selectedGoal.id, Number(normalizeDecimal(simulationAmount)))
      setSimulation(result)
    } catch (apiError) {
      setSimulationError(friendlyError(apiError, 'La simulation'))
    } finally {
      setSimulationLoading(false)
    }
  }

  return (
    <div className="page goals-page">
      <PageHeader
        eyebrow={formatMonth(month)}
        title="Mes objectifs"
        description="Suivez vos projets, répartissez votre capacité d’épargne et testez un rythme mensuel sans modifier vos versements réels."
        action={<button className="button button--primary" onClick={openCreateForm} type="button">Créer un objectif</button>}
      />

      <Card className="goals-toolbar" aria-label="Période et devise des objectifs">
        <label className="field">
          <span>Mois de répartition</span>
          <input disabled={allocationSaving} onChange={(event) => selectPeriod(event.target.value)} type="month" value={month} />
        </label>
        <label className="field">
          <span>Devise</span>
          <select disabled={allocationSaving} onChange={(event) => selectPeriod(month, event.target.value)} value={currency}>
            {CURRENCIES.map((item) => <option key={item} value={item}>{item}</option>)}
          </select>
        </label>
        <label className="goals-archive-toggle">
          <input checked={includeArchived} onChange={(event) => setIncludeArchived(event.target.checked)} type="checkbox" />
          <span>Afficher les objectifs archivés</span>
        </label>
        <p>Les devises restent toujours séparées et ne sont jamais converties.</p>
      </Card>

      {goalSuccess && <InlineNotice live tone="success" icon="check">{goalSuccess}</InlineNotice>}
      {goalsError && (
        <InlineNotice live tone="warning" icon="alert">
          <p>{goalsError}</p>
          <button className="button button--secondary" onClick={() => setGoalReloadToken((value) => value + 1)} type="button">Réessayer</button>
        </InlineNotice>
      )}

      {formMode && (
        <Card className="goal-editor" as="section" aria-labelledby="goal-editor-title">
          <div className="section-heading">
            <div><p className="eyebrow">{formMode === 'edit' ? 'Mise à jour' : 'Nouveau projet'}</p><h2 id="goal-editor-title">{formMode === 'edit' ? `Modifier ${editingGoal.name}` : 'Créer un objectif'}</h2></div>
            <button className="button button--ghost" disabled={goalSaving} onClick={() => setFormMode(null)} type="button">Fermer</button>
          </div>
          {goalFormError && <InlineNotice live tone="warning" icon="alert">{goalFormError}</InlineNotice>}
          <form aria-busy={goalSaving} className="goal-editor__form" onSubmit={submitGoal}>
            <label className="field">
              <span>Nom de l’objectif</span>
              <input aria-invalid={Boolean(goalFormErrors.name)} disabled={goalSaving} maxLength={160} onChange={(event) => updateGoalFormField('name', event.target.value)} value={goalForm.name} />
              {goalFormErrors.name && <small className="field-error">{goalFormErrors.name}</small>}
            </label>
            <label className="field goal-editor__description">
              <span>Description facultative</span>
              <textarea aria-invalid={Boolean(goalFormErrors.description)} disabled={goalSaving} maxLength={1000} onChange={(event) => updateGoalFormField('description', event.target.value)} rows="3" value={goalForm.description} />
              {goalFormErrors.description && <small className="field-error">{goalFormErrors.description}</small>}
            </label>
            <label className="field">
              <span>Type de projet</span>
              <select disabled={goalSaving} onChange={(event) => updateGoalFormField('type', event.target.value)} value={goalForm.type}>
                {GOAL_TYPES.map(([value, label]) => <option key={value} value={value}>{label}</option>)}
              </select>
            </label>
            <label className="field">
              <span>Devise</span>
              <input aria-readonly={formMode === 'edit'} disabled={goalSaving} onChange={(event) => updateGoalFormField('currency', event.target.value)} readOnly={formMode === 'edit'} value={goalForm.currency} />
            </label>
            <label className="field">
              <span>Montant cible ({goalForm.currency})</span>
              <input aria-invalid={Boolean(goalFormErrors.targetAmount)} disabled={goalSaving} inputMode="decimal" onChange={(event) => updateGoalFormField('targetAmount', event.target.value)} placeholder="0,0000" value={goalForm.targetAmount} />
              {goalFormErrors.targetAmount && <small className="field-error">{goalFormErrors.targetAmount}</small>}
            </label>
            <label className="field">
              <span>Montant déjà épargné ({goalForm.currency})</span>
              <input aria-invalid={Boolean(goalFormErrors.initialAmount)} disabled={goalSaving} inputMode="decimal" onChange={(event) => updateGoalFormField('initialAmount', event.target.value)} placeholder="0,0000" value={goalForm.initialAmount} />
              {goalFormErrors.initialAmount && <small className="field-error">{goalFormErrors.initialAmount}</small>}
            </label>
            <label className="field">
              <span>Date cible facultative</span>
              <input disabled={goalSaving} onChange={(event) => updateGoalFormField('targetDate', event.target.value)} type="date" value={goalForm.targetDate} />
            </label>
            <label className="field">
              <span>Priorité</span>
              <select disabled={goalSaving} onChange={(event) => updateGoalFormField('priority', event.target.value)} value={goalForm.priority}>
                {PRIORITIES.map(([value, label]) => <option key={value} value={value}>{label}</option>)}
              </select>
            </label>
            <div className="goal-editor__actions">
              <button className="button button--secondary" disabled={goalSaving} onClick={() => setFormMode(null)} type="button">Annuler</button>
              <button className="button button--primary" disabled={goalSaving} type="submit">{goalSaving ? 'Enregistrement…' : formMode === 'edit' ? 'Enregistrer les modifications' : 'Créer mon objectif'}</button>
            </div>
          </form>
        </Card>
      )}

      <section aria-labelledby="goals-list-title" className="goals-section">
        <div className="section-heading"><div><p className="eyebrow">Projets personnels</p><h2 id="goals-list-title">Vos objectifs en {currency}</h2></div></div>
        {goalsLoading ? (
          <Card className="goals-state" aria-live="polite"><span className="auth-spinner" /> Chargement de vos objectifs…</Card>
        ) : !goalsError && goals.length === 0 ? (
          <Card className="goals-state goals-state--empty">
            <h3>Aucun objectif dans cette devise</h3>
            <p>Commencez par créer un projet simple et réaliste.</p>
            <button className="button button--primary" onClick={openCreateForm} type="button">Créer mon premier objectif</button>
          </Card>
        ) : !goalsError ? (
          <div className="goal-grid goal-grid--detailed">
            {goals.map((goal) => (
              <Card className={`goal-card goal-card--detailed${goal.status === 'ARCHIVED' ? ' is-archived' : ''}`} key={goal.id}>
                <div className="goal-card__top">
                  <div className="goal-card__badges"><Badge tone={STATUS_TONES[goal.status]}>{STATUS_LABELS[goal.status]}</Badge><Badge tone="purple">{PRIORITY_LABELS[goal.priority]}</Badge></div>
                  <span className="goal-card__type">{TYPE_LABELS[goal.type]}</span>
                </div>
                <div><h3>{goal.name}</h3>{goal.description && <p className="muted">{goal.description}</p>}</div>
                <div className="goal-card__amounts"><strong>{formatMoney(goal.currentSaved, goal.currency)}</strong><span>sur {formatMoney(goal.targetAmount, goal.currency)}</span></div>
                <ProgressBar label={`Progression de ${goal.name}`} tone={goal.status === 'ACHIEVED' ? 'mint' : 'teal'} value={goal.progressPercentage} />
                <div className="goal-card__facts">
                  <span>Reste à épargner <strong>{formatMoney(goal.remainingAmount, goal.currency)}</strong></span>
                  <span>Échéance <strong>{formatDate(goal.targetDate)}</strong></span>
                </div>
                {goal.overdue && <InlineNotice tone="warning" icon="alert">La date cible est dépassée, mais votre objectif reste modifiable.</InlineNotice>}
                <div className="goal-card__actions">
                  <button className="button button--secondary" onClick={() => toggleGoalDetails(goal)} type="button">{expandedGoalId === goal.id ? 'Fermer le détail' : 'Voir le détail'}</button>
                  {!['CANCELLED', 'ARCHIVED'].includes(goal.status) && <button className="button button--ghost" onClick={() => openEditForm(goal)} type="button">Modifier</button>}
                  {goal.status === 'ACTIVE' && <button className="button button--ghost" disabled={Boolean(goalActionBusy)} onClick={() => applyStatus(goal, 'PAUSED', `Mettre « ${goal.name} » en pause ?`)} type="button">Mettre en pause</button>}
                  {goal.status === 'PAUSED' && <button className="button button--ghost" disabled={Boolean(goalActionBusy)} onClick={() => applyStatus(goal, 'ACTIVE', `Reprendre « ${goal.name} » ?`)} type="button">Reprendre</button>}
                  {['ACTIVE', 'PAUSED'].includes(goal.status) && <button className="button button--danger-quiet" disabled={Boolean(goalActionBusy)} onClick={() => applyStatus(goal, 'CANCELLED', `Annuler définitivement « ${goal.name} » ?`)} type="button">Annuler</button>}
                  {goal.status !== 'ARCHIVED' && <button className="button button--danger-quiet" disabled={Boolean(goalActionBusy)} onClick={() => handleArchive(goal)} type="button">Archiver</button>}
                </div>
              </Card>
            ))}
          </div>
        ) : null}
      </section>

      {selectedGoal && (
        <Card className="goal-details" as="section" aria-labelledby="goal-details-title">
          <div className="section-heading"><div><p className="eyebrow">Suivi réel</p><h2 id="goal-details-title">Versements de {selectedGoal.name}</h2></div><Badge tone={STATUS_TONES[selectedGoal.status]}>{STATUS_LABELS[selectedGoal.status]}</Badge></div>
          {contributionError && <InlineNotice live tone="warning" icon="alert">{contributionError}</InlineNotice>}
          {contributionSuccess && <InlineNotice live tone="success" icon="check">{contributionSuccess}</InlineNotice>}
          <div className="goal-details__grid">
            <div>
              {contributionsLoading ? <p aria-live="polite" className="muted">Chargement des versements…</p>
                : contributions.length === 0 ? <div className="goals-inline-empty">Aucun versement enregistré pour le moment.</div>
                  : <ul className="contribution-list">{contributions.map((contribution) => (
                    <li key={contribution.id}>
                      <span><strong>{formatMoney(contribution.amount, selectedGoal.currency)}</strong><small>{formatDate(contribution.contributionDate)} · {contribution.source === 'MANUAL' ? 'Versement manuel' : 'Lié à une transaction'}</small></span>
                      {contribution.source === 'MANUAL' && <button className="button button--danger-quiet" disabled={contributionSaving} onClick={() => removeContribution(contribution)} type="button">Supprimer</button>}
                    </li>
                  ))}</ul>}
              {selectedGoal.status === 'ACTIVE' && (
                <form className="contribution-form" onSubmit={submitContribution}>
                  <label className="field"><span>Nouveau versement ({selectedGoal.currency})</span><input aria-invalid={Boolean(contributionFieldError)} disabled={contributionSaving} inputMode="decimal" onChange={(event) => { setContributionForm({ ...contributionForm, amount: event.target.value }); setContributionFieldError('') }} placeholder="0,0000" value={contributionForm.amount} /></label>
                  <label className="field"><span>Date facultative</span><input disabled={contributionSaving} max={todayIso()} onChange={(event) => { setContributionForm({ ...contributionForm, contributionDate: event.target.value }); setContributionFieldError('') }} type="date" value={contributionForm.contributionDate} /></label>
                  {contributionFieldError && <p className="field-error">{contributionFieldError}</p>}
                  <button className="button button--primary" disabled={contributionSaving} type="submit">{contributionSaving ? 'Ajout…' : 'Ajouter le versement'}</button>
                </form>
              )}
            </div>

            {selectedGoal.status === 'ACTIVE' && (
              <div className="goal-simulation">
                <p className="eyebrow">Simulation temporaire</p>
                <h3>Tester un autre rythme mensuel</h3>
                <p className="muted">Cette projection ne modifie ni votre objectif, ni vos versements réels.</p>
                <form onSubmit={submitSimulation}>
                  <label className="field"><span>Contribution mensuelle simulée ({selectedGoal.currency})</span><input aria-invalid={Boolean(simulationError)} disabled={simulationLoading} inputMode="decimal" onChange={(event) => { setSimulationAmount(event.target.value); setSimulationError(''); setSimulation(null) }} placeholder="0,0000" value={simulationAmount} /></label>
                  <button className="button button--secondary" disabled={simulationLoading} type="submit">{simulationLoading ? 'Calcul…' : 'Calculer la projection'}</button>
                </form>
                {simulationError && <InlineNotice live tone="warning" icon="alert">{simulationError}</InlineNotice>}
                {simulation && (
                  <div className={`simulation-result${simulation.reachable ? '' : ' simulation-result--unreachable'}`}>
                    <div>
                      <p>{simulation.reachable ? 'Date d’atteinte estimée' : 'Objectif non atteignable avec ce rythme'}</p>
                      <h3>{simulation.reachable ? formatDate(simulation.estimatedAchievementDate) : 'Aucune date estimée'}</h3>
                      <small>{simulation.reachable ? `${simulation.monthsToTarget} mois environ` : 'Une contribution mensuelle positive est nécessaire.'}</small>
                    </div>
                    <dl>
                      <div><dt>Déjà épargné</dt><dd>{formatMoney(simulation.currentSaved, simulation.currency)}</dd></div>
                      <div><dt>Reste à épargner</dt><dd>{formatMoney(simulation.remainingAmount, simulation.currency)}</dd></div>
                      <div><dt>Contribution testée</dt><dd>{formatMoney(simulation.simulatedMonthlyContribution, simulation.currency)}</dd></div>
                      {simulation.targetDate && <div><dt>Date cible</dt><dd>{simulation.meetsTargetDate ? 'Projection compatible' : 'Projection au-delà de la cible'}</dd></div>}
                    </dl>
                    <p className="simulation-disclaimer">Estimation déterministe, fournie à titre indicatif et sans garantie.</p>
                  </div>
                )}
              </div>
            )}
          </div>
        </Card>
      )}

      <Card className="goal-allocation" as="section" aria-labelledby="goal-allocation-title">
        <div className="section-heading"><div><p className="eyebrow">Capacité Salary Planning</p><h2 id="goal-allocation-title">Répartition de {formatMonth(month)}</h2></div>{pastMonth && <Badge tone="neutral">Lecture seule</Badge>}</div>
        {allocationError && (
          <InlineNotice live tone="warning" icon="alert">
            <p>{allocationError}</p>
            {!allocation && <button className="button button--secondary" onClick={() => setAllocationReloadToken((value) => value + 1)} type="button">Réessayer</button>}
          </InlineNotice>
        )}
        {allocationSuccess && <InlineNotice live tone="success" icon="check">{allocationSuccess}</InlineNotice>}
        {allocationLoading ? <div className="goals-state" aria-live="polite"><span className="auth-spinner" /> Chargement de la capacité mensuelle…</div>
          : allocation ? (
            <>
              {!allocation.capacityAvailable && (
                <InlineNotice tone="info" icon="plan">
                  <p>Aucun plan de salaire n’existe pour ce mois et cette devise. Aucune capacité n’est disponible.</p>
                  <Link className="button button--secondary" to="/planification-salaire">Préparer mon Salary Planning</Link>
                </InlineNotice>
              )}
              {allocation.capacityAvailable && Number(allocation.salaryPlanSavingCapacity) < 0 && (
                <InlineNotice tone="warning" icon="alert">Votre Salary Planning présente un déficit de <strong>{formatMoney(Math.abs(Number(allocation.salaryPlanSavingCapacity)), currency)}</strong>. Vous pouvez consulter vos objectifs, mais pas leur attribuer un montant positif.</InlineNotice>
              )}
              {allocation.capacityAvailable && Number(allocation.salaryPlanSavingCapacity) === 0 && (
                <InlineNotice tone="info" icon="info">La capacité d’épargne est nulle pour ce mois. Vous pouvez uniquement retirer une répartition existante.</InlineNotice>
              )}

              <div className="goal-capacity-metrics" aria-label="Capacité d’épargne enregistrée">
                <div><span>Capacité du plan</span><strong>{allocation.capacityAvailable ? formatMoney(allocation.salaryPlanSavingCapacity, currency) : 'Indisponible'}</strong></div>
                <div><span>Capacité répartissable</span><strong>{formatMoney(allocation.allocatableCapacity, currency)}</strong></div>
                <div><span>Total confirmé</span><strong>{formatMoney(allocation.totalConfirmedAllocation, currency)}</strong></div>
                <div><span>Reste disponible</span><strong>{formatMoney(allocation.remainingUnallocatedCapacity, currency)}</strong></div>
              </div>

              {allocation.allocations.length > 0 && (
                <div className="saved-allocation-list" aria-label="Allocations enregistrées">
                  <h3>Répartition enregistrée</h3>
                  {allocation.allocations.map((item) => (
                    <div key={item.goalId}>
                      <span><strong>{item.goalName}</strong><small>{STATUS_LABELS[item.goalStatus]} · {item.allocationStatus === 'CONFIRMED' ? 'Confirmée' : item.allocationStatus === 'CANCELLED' ? 'Annulée' : 'Proposée'}</small></span>
                      <span><strong>{formatMoney(item.allocatedAmount, currency)}</strong>{item.projection && <small>{item.projection.reachable ? `Date estimée : ${formatDate(item.projection.estimatedAchievementDate)} (${item.projection.monthsToTarget} mois)` : 'Projection non atteignable'} · sans garantie</small>}</span>
                    </div>
                  ))}
                </div>
              )}

              <form aria-busy={allocationSaving} className="goal-allocation-form" onSubmit={saveAllocations}>
                <div className="section-heading"><div><p className="eyebrow">Choix manuel</p><h3>Répartir la capacité disponible</h3></div></div>
                {activeGoals.length === 0 ? <div className="goals-inline-empty">Aucun objectif actif dans cette devise.</div>
                  : <div className="goal-allocation-editor">{activeGoals.map((goal) => (
                    <label className="field" key={goal.id}>
                      <span>{goal.name} ({currency})</span>
                      <input
                        aria-invalid={Boolean(allocationFieldErrors[goal.id])}
                        disabled={!allocation.capacityAvailable || pastMonth || allocationSaving}
                        inputMode="decimal"
                        onChange={(event) => updateAllocation(goal.id, event.target.value)}
                        placeholder="Aucune allocation"
                        value={allocationValues[goal.id] ?? ''}
                      />
                      {allocationFieldErrors[goal.id] && <small className="field-error">{allocationFieldErrors[goal.id]}</small>}
                    </label>
                  ))}</div>}
                <div className={`allocation-draft-summary${allocationEstimate.overCapacity ? ' is-over-capacity' : ''}`}>
                  <span><small>Estimation avant enregistrement</small><strong>Total saisi : {formatScale4(allocationEstimate.valid ? allocationEstimate.total : null, currency)}</strong></span>
                  <span><small>Reste estimé</small><strong>{formatScale4(allocationEstimate.valid ? allocationEstimate.remaining : null, currency)}</strong></span>
                </div>
                {allocationEstimate.overCapacity && <p className="field-error" role="alert">Le total estimé dépasse la capacité répartissable.</p>}
                <div className="goal-allocation-actions">
                  <p>{allocationDirty ? 'Répartition non enregistrée.' : 'Répartition à jour.'}</p>
                  <button className="button button--primary" disabled={!allocation.capacityAvailable || pastMonth || allocationSaving || !allocationDirty || !allocationEstimate.valid || allocationEstimate.overCapacity} type="submit">{allocationSaving ? 'Enregistrement…' : 'Enregistrer la répartition'}</button>
                </div>
              </form>
            </>
          ) : null}
      </Card>
    </div>
  )
}
