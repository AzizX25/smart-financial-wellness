import { useEffect, useMemo, useState } from 'react'
import Icon from '../components/Icon'
import { Badge, Card, formatCurrency, InlineNotice, PageHeader } from '../components/Ui'
import { useFinancialData } from '../financial/FinancialDataContext'
import { ApiError } from '../services/apiClient'
import { assignTransactionCategory, listCategories } from '../services/categoryApi'
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

export default function TransactionsPage() {
  const { accounts, connected, dataVersion, historical, synchronizationError, synchronize, synchronizing } = useFinancialData()
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
    } catch {
      setAssignmentError('La catégorie de cette transaction n’a pas pu être modifiée.')
    } finally {
      setAssigningId('')
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
      {historical && <InlineNotice tone="warning" icon="info">Historique consultable hors connexion bancaire. Reconnectez la banque pour recevoir de nouvelles transactions.</InlineNotice>}
      {!connected && accounts.length === 0 && <InlineNotice tone="info" icon="bank">Aucune donnée bancaire synchronisée n’est disponible.</InlineNotice>}
      {synchronizationError && <InlineNotice live tone="warning" icon="alert">{synchronizationError} Les transactions précédentes restent affichées.</InlineNotice>}
      {error && <InlineNotice live tone="warning" icon="alert">{error} {result.items.length > 0 && 'La page précédente reste affichée.'}</InlineNotice>}
      {assignmentError && <InlineNotice live tone="warning" icon="alert">{assignmentError}</InlineNotice>}

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
            </article>)}
      </section>
      {result.totalPages > 1 && <nav aria-label="Pagination des transactions" className="pagination-controls"><button className="button button--secondary" disabled={loading || result.first} onClick={() => setPage((value) => Math.max(0, value - 1))} type="button">Précédente</button><span>Page {result.page + 1} sur {result.totalPages}</span><button className="button button--secondary" disabled={loading || result.last} onClick={() => setPage((value) => value + 1)} type="button">Suivante</button></nav>}
    </div>
  )
}
