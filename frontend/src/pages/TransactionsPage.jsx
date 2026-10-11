import { useEffect, useMemo, useState } from 'react'
import Icon from '../components/Icon'
import CsvImportPanel from '../components/CsvImportPanel'
import { Badge, Card, formatCurrency, InlineNotice, PageHeader } from '../components/Ui'
import { useFinancialData } from '../financial/FinancialDataContext'
import { ApiError } from '../services/apiClient'
import {
  acceptMerchantSuggestion,
  assignTransactionCategory,
  forgetMerchantMemory,
  listCategorizationReviews,
  listCategories,
  listMerchantMemories,
  listMerchantSuggestions,
  rejectMerchantSuggestion,
  runMerchantRecognition,
} from '../services/categoryApi'
import { listLocalTransactions } from '../services/financialApi'

const directions = [
  { id: '', label: 'Toutes' },
  { id: 'DEBIT', label: 'Débits' },
  { id: 'CREDIT', label: 'Crédits' },
]

function formatDate(value) {
  if (!value) return 'Date inconnue'
  const date = new Date(`${value}T00:00:00`)
  return Number.isNaN(date.getTime()) ? value : new Intl.DateTimeFormat('fr-TN', { dateStyle: 'medium' }).format(date)
}

function controlledTransactionError(error) {
  if (error instanceof ApiError && error.status === 400) return 'Vérifiez les dates et les filtres sélectionnés.'
  if (error instanceof ApiError && error.status === 0) return 'Le service financier est momentanément inaccessible.'
  return 'Les transactions n’ont pas pu être chargées.'
}

const emptyPage = { items: [], page: 0, size: 20, totalElements: 0, totalPages: 0, first: true, last: true }
const merchantMethodLabels = {
  EXACT_ALIAS: 'Alias connu',
  FUZZY_ALIAS: 'Correspondance proche',
  ML: 'Suggestion intelligente',
  WEB_LLM: 'Recherche web + IA locale',
  USER_MEMORY: 'Appris de vos choix',
}

const reviewStatusLabels = {
  SUGGESTION_PENDING: 'Suggestion à confirmer',
  NEEDS_REVIEW: 'À vérifier',
  EXCLUDED: 'Analyse non disponible',
}

const reviewReasonLabels = {
  NOT_ANALYZED: 'Cette transaction n’a pas encore été analysée.',
  NO_RELIABLE_SUGGESTION: 'Aucune suggestion suffisamment fiable n’a été trouvée.',
  MODEL_ABSTAINED: 'Le modèle préfère vous laisser choisir plutôt que de proposer une catégorie incertaine.',
  SUGGESTION_REJECTED: 'La suggestion a été refusée. Choisissez une catégorie si vous le souhaitez.',
  MERCHANT_TEXT_UNAVAILABLE: 'Le libellé disponible ne permet pas d’identifier le marchand.',
  SERVICE_UNAVAILABLE: 'Le service de suggestion était indisponible. Le choix manuel reste possible.',
  INVALID_RESPONSE: 'La réponse du service n’a pas pu être vérifiée.',
  MODEL_VERSION_MISMATCH: 'La version du modèle ne correspond pas à celle attendue.',
  NOT_POSTED: 'Cette opération bancaire n’est pas encore comptabilisée.',
  REVERSED: 'Cette opération a été annulée par la banque.',
  INTERNAL_TRANSFER: 'Cette opération fait partie d’un transfert entre vos comptes.',
  UNSUPPORTED_CURRENCY: 'L’analyse automatique prend actuellement en charge les débits en TND uniquement.',
  CREDIT_NOT_SUPPORTED: 'L’analyse automatique des crédits n’est pas encore disponible.',
  CATEGORY_REMOVED: 'La catégorie précédente a été retirée.',
}

function safeEvidenceUrls(urls) {
  if (!Array.isArray(urls)) return []
  return urls.filter((value) => {
    try {
      const url = new URL(value)
      return url.protocol === 'https:' && url.hostname && !url.username && !url.password
    } catch {
      return false
    }
  })
}

function MerchantSuggestion({ busy, onReview, suggestion, transaction }) {
  const webEnriched = suggestion.matchMethod === 'WEB_LLM'
  const userMemory = suggestion.matchMethod === 'USER_MEMORY'
  const evidenceUrls = webEnriched ? safeEvidenceUrls(suggestion.evidenceUrls) : []

  return (
    <div className="merchant-suggestion" aria-label={`Suggestion pour ${transaction.description}`}>
      <div>
        <strong>{suggestion.canonicalMerchant}</strong>
        <span>{suggestion.categoryName} · {merchantMethodLabels[suggestion.matchMethod] || 'Suggestion intelligente'}</span>
      </div>
      {webEnriched ? (
        <>
          {suggestion.rationale && <p>{suggestion.rationale}</p>}
          {evidenceUrls.length > 0 && (
            <div className="merchant-suggestion__evidence">
              <span>Sources publiques consultées</span>
              <ul>{evidenceUrls.map((url, index) => <li key={url}><a href={url} rel="noopener noreferrer" target="_blank">Voir la source {index + 1}</a></li>)}</ul>
            </div>
          )}
          <p className="merchant-suggestion__warning">Suggestion expérimentale, informative et non garantie. Vérifiez la catégorie avant de l'accepter.</p>
        </>
      ) : userMemory ? (
        <p>Cette suggestion reprend un choix que vous avez confirmé auparavant. Elle reste privée à votre compte et nécessite votre validation.</p>
      ) : (
        <p>Suggestion expérimentale fondée sur un modèle entraîné avec des libellés marchands synthétiques. Vérifiez la catégorie avant de l'accepter.</p>
      )}
      <div className="merchant-suggestion__actions">
        <button className="button button--primary" disabled={busy} onClick={() => onReview(transaction, suggestion, true)} type="button">Accepter</button>
        <button className="button button--secondary" disabled={busy} onClick={() => onReview(transaction, suggestion, false)} type="button">Refuser</button>
      </div>
    </div>
  )
}

function CategorizationReview({
  busy,
  categories,
  choice,
  onChoice,
  onConfirm,
  onReviewSuggestion,
  review,
}) {
  const statusLabel = reviewStatusLabels[review.reviewStatus] || 'À vérifier'
  const reason = reviewReasonLabels[review.reasonCode] || 'Cette transaction nécessite votre vérification.'

  return (
    <article className={`categorization-review categorization-review--${review.reviewStatus.toLowerCase()}`}>
      <div className="categorization-review__heading">
        <div>
          <Badge tone={review.reviewStatus === 'EXCLUDED' ? 'peach' : 'gold'}>{statusLabel}</Badge>
          <h3>{review.description || 'Opération sans libellé'}</h3>
          <p>{formatDate(review.bookingDate)} · {review.direction === 'CREDIT' ? 'Crédit' : 'Débit'}</p>
        </div>
        <strong className={review.direction === 'CREDIT' ? 'amount-credit' : 'amount-debit'}>
          {review.direction === 'CREDIT' ? '+' : '−'} {formatCurrency(review.amount, review.currency)}
        </strong>
      </div>

      {review.pendingSuggestion ? (
        <MerchantSuggestion
          busy={busy}
          onReview={onReviewSuggestion}
          suggestion={review.pendingSuggestion}
          transaction={{ id: review.transactionId, description: review.description }}
        />
      ) : (
        <>
          <p className="categorization-review__reason">{reason}</p>
          {review.candidateCategories.length > 0 && (
            <div className="categorization-review__candidates" aria-label="Catégories proposées à vérifier">
              <span>Suggestions à vérifier — aucun choix n’est appliqué automatiquement</span>
              <div>{review.candidateCategories.map((candidate) => (
                <button
                  aria-pressed={choice === candidate.categoryId}
                  className="button button--secondary"
                  disabled={busy}
                  key={candidate.categoryId}
                  onClick={() => onChoice(review.transactionId, candidate.categoryId)}
                  type="button"
                >
                  {candidate.categoryName}
                </button>
              ))}</div>
            </div>
          )}
          {review.manualCategorizationAllowed && (
            <div className="categorization-review__manual">
              <label>
                <span>Choisir une catégorie</span>
                <select
                  aria-label={`Choisir une catégorie pour ${review.description}`}
                  disabled={busy}
                  onChange={(event) => onChoice(review.transactionId, event.target.value)}
                  value={choice || ''}
                >
                  <option value="">Sélectionnez une catégorie</option>
                  {categories.map((category) => <option key={category.id} value={category.id}>{category.name}</option>)}
                </select>
              </label>
              <button
                className="button button--primary"
                disabled={busy || !choice}
                onClick={() => onConfirm(review)}
                type="button"
              >
                Confirmer ce choix
              </button>
            </div>
          )}
        </>
      )}
    </article>
  )
}

export default function TransactionsPage() {
  const {
    accounts,
    connected,
    dataVersion,
    historical,
    reload: reloadFinancialData,
    synchronizationError,
    synchronize,
    synchronizing,
  } = useFinancialData()
  const [accountId, setAccountId] = useState('')
  const [direction, setDirection] = useState('')
  const [categoryFilter, setCategoryFilter] = useState('')
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [search, setSearch] = useState('')
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(20)
  const [result, setResult] = useState(emptyPage)
  const [categories, setCategories] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [assignmentError, setAssignmentError] = useState('')
  const [assigningId, setAssigningId] = useState('')
  const [merchantSuggestions, setMerchantSuggestions] = useState({})
  const [recognitionVersion, setRecognitionVersion] = useState(0)
  const [analyzingMerchants, setAnalyzingMerchants] = useState(false)
  const [suggestionActionId, setSuggestionActionId] = useState('')
  const [merchantMessage, setMerchantMessage] = useState('')
  const [merchantError, setMerchantError] = useState('')
  const [reviewResult, setReviewResult] = useState(emptyPage)
  const [reviewPage, setReviewPage] = useState(0)
  const [reviewLoading, setReviewLoading] = useState(true)
  const [reviewError, setReviewError] = useState('')
  const [reviewChoices, setReviewChoices] = useState({})
  const [merchantMemories, setMerchantMemories] = useState([])
  const [memoryLoading, setMemoryLoading] = useState(true)
  const [memoryError, setMemoryError] = useState('')
  const [forgettingMemoryId, setForgettingMemoryId] = useState('')
  const accountNames = useMemo(() => new Map(accounts.map((account) => [account.id, account.displayName])), [accounts])

  useEffect(() => {
    let active = true
    listCategories().then((items) => { if (active) setCategories(items) }).catch(() => {
      if (active) setAssignmentError('Les catégories n’ont pas pu être chargées.')
    })
    return () => { active = false }
  }, [dataVersion])

  useEffect(() => {
    let active = true
    setLoading(true)
    setError('')
    listLocalTransactions({
      accountId,
      categoryId: categoryFilter === 'UNCATEGORIZED' ? '' : categoryFilter,
      direction,
      from,
      page,
      search,
      size,
      to,
      uncategorized: categoryFilter === 'UNCATEGORIZED',
    }).then((response) => { if (active) setResult(response) })
      .catch((requestError) => { if (active) setError(controlledTransactionError(requestError)) })
      .finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [accountId, categoryFilter, dataVersion, direction, from, page, search, size, to])

  useEffect(() => {
    let active = true
    listMerchantSuggestions()
      .then((items) => {
        if (active) setMerchantSuggestions(Object.fromEntries(items.map((item) => [item.transactionId, item])))
      })
      .catch(() => { if (active) setMerchantError('Les suggestions marchandes n’ont pas pu être chargées.') })
    return () => { active = false }
  }, [dataVersion, recognitionVersion])

  useEffect(() => {
    let active = true
    setReviewLoading(true)
    setReviewError('')
    listCategorizationReviews(reviewPage, 20)
      .then((response) => { if (active) setReviewResult(response) })
      .catch(() => { if (active) setReviewError('Les transactions à vérifier n’ont pas pu être chargées.') })
      .finally(() => { if (active) setReviewLoading(false) })
    return () => { active = false }
  }, [dataVersion, recognitionVersion, reviewPage])

  useEffect(() => {
    let active = true
    setMemoryLoading(true)
    setMemoryError('')
    listMerchantMemories()
      .then((items) => { if (active) setMerchantMemories(items) })
      .catch(() => { if (active) setMemoryError('Vos choix marchands appris n’ont pas pu être chargés.') })
      .finally(() => { if (active) setMemoryLoading(false) })
    return () => { active = false }
  }, [dataVersion, recognitionVersion])

  function resetPage(update) {
    setPage(0)
    update()
  }

  function allowedCategories(transaction) {
    return categories.filter((category) => category.code !== 'UNCATEGORIZED' && (category.nature === 'TRANSFER'
      || category.nature === 'NEUTRAL'
      || (transaction.direction === 'DEBIT' && category.nature === 'EXPENSE')
      || (transaction.direction === 'CREDIT' && category.nature === 'INCOME')))
  }

  function chooseReviewCategory(transactionId, categoryId) {
    setReviewChoices((current) => ({ ...current, [transactionId]: categoryId }))
  }

  async function assignCategory(transaction, categoryId) {
    setAssigningId(transaction.id)
    setAssignmentError('')
    try {
      const assignment = await assignTransactionCategory(transaction.id, categoryId)
      setResult((current) => ({
        ...current,
        items: current.items.map((item) => item.id === transaction.id ? {
          ...item,
          categoryId: assignment.categoryId,
          categoryName: assignment.categoryName,
          categoryNature: assignment.categoryNature,
          categoryAssignmentSource: assignment.assignmentSource,
          categorizedAt: assignment.categorizedAt,
        } : item),
      }))
      setMerchantSuggestions((current) => {
        const next = { ...current }
        delete next[transaction.id]
        return next
      })
      setRecognitionVersion((value) => value + 1)
      await reloadFinancialData({ quiet: true })
      return true
    } catch {
      setAssignmentError('La catégorie de cette transaction n’a pas pu être modifiée.')
      return false
    } finally {
      setAssigningId('')
    }
  }

  async function confirmReviewCategory(review) {
    const selectedCategory = reviewChoices[review.transactionId]
    if (!selectedCategory) return
    const changed = await assignCategory({ id: review.transactionId }, selectedCategory)
    if (changed) {
      setReviewChoices((current) => {
        const next = { ...current }
        delete next[review.transactionId]
        return next
      })
      setMerchantMessage('Catégorie confirmée. Votre choix privé pourra être reproposé pour ce marchand.')
    }
  }

  async function analyzeMerchants() {
    setAnalyzingMerchants(true)
    setMerchantError('')
    setMerchantMessage('')
    try {
      const response = await runMerchantRecognition()
      if (response.outcome === 'SERVICE_UNAVAILABLE') {
        setMerchantError('Le service de suggestion est momentanément indisponible. Vos transactions restent inchangées.')
      } else if (response.outcome === 'INVALID_RESPONSE' || response.outcome === 'MODEL_VERSION_MISMATCH') {
        setMerchantError('Les suggestions reçues n’ont pas pu être vérifiées. Aucune catégorie n’a été modifiée.')
      } else if (response.suggestedCount > 0) {
        setMerchantMessage(`${response.suggestedCount} suggestion${response.suggestedCount > 1 ? 's' : ''} à vérifier.`)
      } else if (response.outcome === 'UNSUPPORTED_CURRENCY') {
        setMerchantMessage('Aucune suggestion : cette version analyse uniquement les débits en TND.')
      } else {
        setMerchantMessage('Aucune suggestion fiable n’a été trouvée. Vos transactions restent non catégorisées.')
      }
      setRecognitionVersion((value) => value + 1)
    } catch {
      setMerchantError('L’analyse n’a pas pu être lancée. Vos transactions restent inchangées.')
    } finally {
      setAnalyzingMerchants(false)
    }
  }

  async function reviewSuggestion(transaction, suggestion, accepted) {
    setSuggestionActionId(suggestion.predictionId)
    setMerchantError('')
    try {
      if (accepted) {
        const assignment = await acceptMerchantSuggestion(transaction.id, suggestion.predictionId)
        setResult((current) => ({
          ...current,
          items: current.items.map((item) => item.id === transaction.id ? {
            ...item,
            categoryId: assignment.categoryId,
            categoryName: assignment.categoryName,
            categoryNature: assignment.categoryNature,
            categoryAssignmentSource: assignment.assignmentSource,
            categorizedAt: assignment.categorizedAt,
          } : item),
        }))
        setMerchantMessage('Suggestion acceptée. La catégorie de la transaction a été mise à jour.')
        await reloadFinancialData({ quiet: true })
      } else {
        await rejectMerchantSuggestion(transaction.id, suggestion.predictionId)
        setMerchantMessage('Suggestion refusée. La transaction reste non catégorisée.')
      }
      setMerchantSuggestions((current) => {
        const next = { ...current }
        delete next[transaction.id]
        return next
      })
      setRecognitionVersion((value) => value + 1)
    } catch {
      setMerchantError('Cette suggestion n’a pas pu être traitée. Actualisez la page puis réessayez.')
    } finally {
      setSuggestionActionId('')
    }
  }

  async function forgetMemory(memory) {
    if (!window.confirm(`Oublier le choix appris pour « ${memory.normalizedMerchant} » ?`)) return
    setForgettingMemoryId(memory.id)
    setMemoryError('')
    try {
      await forgetMerchantMemory(memory.id)
      setMerchantMemories((current) => current.filter((item) => item.id !== memory.id))
      setMerchantMessage('Ce choix marchand a été oublié. Les transactions déjà catégorisées restent inchangées.')
    } catch {
      setMemoryError('Ce choix appris n’a pas pu être oublié. Actualisez la page puis réessayez.')
    } finally {
      setForgettingMemoryId('')
    }
  }

  return (
    <div className="page">
      <PageHeader
        eyebrow="Historique local"
        title="Mes transactions"
        description="Opérations normalisées, catégorisées et conservées dans l’application."
        action={(
          <button className="button button--primary" disabled={!connected || synchronizing} onClick={synchronize} type="button">
            <Icon name="transactions" size={18} />
            {synchronizing ? 'Synchronisation en cours…' : 'Actualiser'}
          </button>
        )}
      />
      <CsvImportPanel />
      {historical && <InlineNotice tone="warning" icon="info">Historique consultable hors connexion bancaire. Reconnectez la banque pour recevoir de nouvelles transactions.</InlineNotice>}
      {!connected && accounts.length === 0 && <InlineNotice tone="info" icon="bank">Connectez une banque ou importez un relevé CSV pour ajouter vos premières opérations.</InlineNotice>}
      {synchronizationError && <InlineNotice live tone="warning" icon="alert">{synchronizationError} Les transactions précédentes restent affichées.</InlineNotice>}
      {error && <InlineNotice live tone="warning" icon="alert">{error} {result.items.length > 0 && 'La page précédente reste affichée.'}</InlineNotice>}
      {assignmentError && <InlineNotice live tone="warning" icon="alert">{assignmentError}</InlineNotice>}
      {merchantError && <InlineNotice live tone="warning" icon="alert">{merchantError}</InlineNotice>}
      {reviewError && <InlineNotice live tone="warning" icon="alert">{reviewError}</InlineNotice>}
      {memoryError && <InlineNotice live tone="warning" icon="alert">{memoryError}</InlineNotice>}
      {merchantMessage && <InlineNotice live tone="success" icon="info">{merchantMessage}</InlineNotice>}

      <Card className="merchant-recognition-panel">
        <div><h2>Suggestions de catégories</h2><p>Analyse expérimentale des débits non catégorisés en TND, toujours soumise à votre validation.</p></div>
        <button className="button button--secondary" disabled={analyzingMerchants} onClick={analyzeMerchants} type="button">
          <Icon name="search" size={18} />
          {analyzingMerchants ? 'Analyse en cours…' : 'Analyser les transactions non catégorisées'}
        </button>
      </Card>

      <section className="categorization-review-panel" aria-busy={reviewLoading} aria-label="Transactions à catégoriser">
        <div className="categorization-review-panel__header">
          <div>
            <p className="eyebrow">Validation humaine</p>
            <h2>Transactions à catégoriser</h2>
            <p>Chaque opération non catégorisée reste visible, même lorsque l’analyse automatique préfère s’abstenir.</p>
          </div>
          {!reviewError && <Badge tone="blue">{reviewResult.totalElements} à traiter</Badge>}
        </div>
        {reviewLoading && reviewResult.items.length === 0 ? (
          <Card className="bank-state"><span className="auth-spinner" /> Chargement des transactions à vérifier…</Card>
        ) : !reviewError && reviewResult.items.length === 0 ? (
          <Card className="empty-state"><Icon name="check" /><h3>Tout est à jour</h3><p>Aucune transaction ne demande votre attention.</p></Card>
        ) : (
          <div className="categorization-review-list">
            {reviewResult.items.map((review) => (
              <CategorizationReview
                busy={assigningId === review.transactionId || suggestionActionId === review.pendingSuggestion?.predictionId}
                categories={allowedCategories(review)}
                choice={reviewChoices[review.transactionId]}
                key={review.transactionId}
                onChoice={chooseReviewCategory}
                onConfirm={confirmReviewCategory}
                onReviewSuggestion={reviewSuggestion}
                review={review}
              />
            ))}
          </div>
        )}
        {reviewResult.totalPages > 1 && (
          <nav aria-label="Pagination des transactions à catégoriser" className="pagination-controls">
            <button className="button button--secondary" disabled={reviewLoading || reviewResult.first} onClick={() => setReviewPage((value) => Math.max(0, value - 1))} type="button">Précédente</button>
            <span>Page {reviewResult.page + 1} sur {reviewResult.totalPages}</span>
            <button className="button button--secondary" disabled={reviewLoading || reviewResult.last} onClick={() => setReviewPage((value) => value + 1)} type="button">Suivante</button>
          </nav>
        )}
      </section>

      <Card className="merchant-memory-panel">
        <div className="merchant-memory-panel__header">
          <div><h2>Choix marchands appris</h2><p>Ces associations viennent uniquement de vos confirmations et restent privées à votre compte.</p></div>
          <Badge tone="teal">{merchantMemories.length}</Badge>
        </div>
        {memoryLoading && merchantMemories.length === 0 ? (
          <p className="muted">Chargement de vos choix appris…</p>
        ) : !memoryError && merchantMemories.length === 0 ? (
          <p className="muted">Aucun choix appris pour le moment.</p>
        ) : (
          <div className="merchant-memory-list">{merchantMemories.map((memory) => (
            <div key={memory.id}>
              <span><strong>{memory.normalizedMerchant}</strong><small>{memory.categoryName} · {memory.direction === 'CREDIT' ? 'Crédit' : 'Débit'} · {memory.currency}</small></span>
              <button className="button button--ghost" disabled={forgettingMemoryId === memory.id} onClick={() => forgetMemory(memory)} type="button">Oublier</button>
            </div>
          ))}</div>
        )}
      </Card>

      <Card className="transaction-toolbar transaction-toolbar--dynamic">
        <label className="search-field"><span className="sr-only">Rechercher une transaction</span><Icon name="search" size={20} /><input maxLength={160} onChange={(event) => resetPage(() => setSearch(event.target.value))} placeholder="Description ou contrepartie" type="search" value={search} /></label>
        <div className="segmented-control" aria-label="Filtrer par sens">{directions.map((item) => <button aria-pressed={direction === item.id} className={direction === item.id ? 'is-active' : ''} key={item.id} onClick={() => resetPage(() => setDirection(item.id))} type="button">{item.label}</button>)}</div>
        <label className="select-field"><span>Compte</span><select onChange={(event) => resetPage(() => setAccountId(event.target.value))} value={accountId}><option value="">Tous les comptes</option>{accounts.map((account) => <option key={account.id} value={account.id}>{account.displayName}</option>)}</select></label>
        <label className="select-field"><span>Catégorie</span><select onChange={(event) => resetPage(() => setCategoryFilter(event.target.value))} value={categoryFilter}><option value="">Toutes les catégories</option><option value="UNCATEGORIZED">Non catégorisées</option>{categories.filter((category) => category.code !== 'UNCATEGORIZED').map((category) => <option key={category.id} value={category.id}>{category.name}</option>)}</select></label>
        <label className="date-filter"><span>Du</span><input onChange={(event) => resetPage(() => setFrom(event.target.value))} type="date" value={from} /></label>
        <label className="date-filter"><span>Au</span><input onChange={(event) => resetPage(() => setTo(event.target.value))} type="date" value={to} /></label>
        <label className="select-field"><span>Par page</span><select onChange={(event) => resetPage(() => setSize(Number(event.target.value)))} value={size}><option value={10}>10</option><option value={20}>20</option><option value={50}>50</option></select></label>
      </Card>

      <section aria-busy={loading} aria-live="polite" aria-label="Liste des transactions" className="transaction-list">
        <div className="transaction-list__summary"><p><strong>{result.totalElements} transaction{result.totalElements === 1 ? '' : 's'}</strong></p><p>Page {result.totalPages === 0 ? 0 : result.page + 1} sur {result.totalPages}</p></div>
        {loading && result.items.length === 0 ? <Card className="bank-state"><span className="auth-spinner" /> Chargement des transactions…</Card>
          : result.items.length === 0 ? <Card className="empty-state"><Icon name="search" /><h2>Aucune transaction</h2><p>Aucune opération ne correspond aux filtres sélectionnés.</p></Card>
            : result.items.map((transaction) => <article className="transaction-row" key={transaction.id}>
              <span className={`transaction-row__icon transaction-row__icon--${transaction.direction === 'CREDIT' ? 'income' : 'expense'}`} aria-hidden="true">{transaction.direction === 'CREDIT' ? '↓' : '↑'}</span>
              <div className="transaction-row__main"><h2>{transaction.description || 'Opération sans libellé'}</h2><p>{formatDate(transaction.bookingDate)} · {accountNames.get(transaction.accountId) || 'Compte synchronisé'}</p>{transaction.counterpartyName && <small>{transaction.counterpartyName}</small>}<label className="transaction-category-select"><span className="sr-only">Catégorie de {transaction.description}</span><select aria-label={`Catégorie de ${transaction.description}`} disabled={assigningId === transaction.id} onChange={(event) => assignCategory(transaction, event.target.value)} value={transaction.categoryId || ''}><option value="">Non catégorisée</option>{allowedCategories(transaction).map((category) => <option key={category.id} value={category.id}>{category.name}</option>)}</select>{transaction.categoryAssignmentSource === 'MANUAL' && <small>Choix manuel</small>}</label></div>
              <Badge tone={transaction.direction === 'CREDIT' ? 'mint' : 'peach'}>{transaction.direction === 'CREDIT' ? 'Crédit' : 'Débit'}</Badge>
              <div className="transaction-row__amount"><strong className={transaction.direction === 'CREDIT' ? 'amount-credit' : 'amount-debit'}>{transaction.direction === 'CREDIT' ? '+' : '−'} {formatCurrency(transaction.amount, transaction.currency)}</strong><small>{transaction.currency}</small></div>
              {merchantSuggestions[transaction.id] && <MerchantSuggestion busy={suggestionActionId === merchantSuggestions[transaction.id].predictionId} onReview={reviewSuggestion} suggestion={merchantSuggestions[transaction.id]} transaction={transaction} />}
            </article>)}
      </section>
      {result.totalPages > 1 && <nav aria-label="Pagination des transactions" className="pagination-controls"><button className="button button--secondary" disabled={loading || result.first} onClick={() => setPage((value) => Math.max(0, value - 1))} type="button">Précédente</button><span>Page {result.page + 1} sur {result.totalPages}</span><button className="button button--secondary" disabled={loading || result.last} onClick={() => setPage((value) => value + 1)} type="button">Suivante</button></nav>}
    </div>
  )
}
