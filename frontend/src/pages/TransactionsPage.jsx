import { useMemo, useState } from 'react'
import Icon from '../components/Icon'
import { Badge, Card, formatTnd, InlineNotice, PageHeader } from '../components/Ui'
import { transactions } from '../data/demoData'

const filters = [
  { id: 'ALL', label: 'Toutes' },
  { id: 'DEBIT', label: 'Dépenses' },
  { id: 'CREDIT', label: 'Revenus' },
]

export default function TransactionsPage() {
  const [search, setSearch] = useState('')
  const [filter, setFilter] = useState('ALL')
  const [showImport, setShowImport] = useState(false)

  const visibleTransactions = useMemo(() => {
    const query = search.trim().toLocaleLowerCase('fr')
    return transactions.filter((transaction) => {
      const matchesFilter = filter === 'ALL' || transaction.direction === filter
      const matchesSearch = !query || `${transaction.merchant} ${transaction.category}`.toLocaleLowerCase('fr').includes(query)
      return matchesFilter && matchesSearch
    })
  }, [filter, search])

  return (
    <div className="page">
      <PageHeader
        eyebrow="Historique"
        title="Mes transactions"
        description="Retrouvez facilement où va votre argent, avec des catégories simples."
        action={(
          <button className="button button--primary" type="button" onClick={() => setShowImport((visible) => !visible)}>
            <Icon name="upload" size={19} /> Importer CSV / XLSX
          </button>
        )}
      />

      {showImport && (
        <Card className="import-preview">
          <div className="card-heading">
            <div><p className="eyebrow">Aperçu de l'import</p><h2>Associer les colonnes du fichier</h2></div>
            <Badge tone="teal">3 colonnes reconnues</Badge>
          </div>
          <p className="muted">Le fichier reste la source de référence. Vous pourrez confirmer chaque association avant l'import.</p>
          <div className="mapping-steps">
            <div><span>1</span><strong>Choisir un fichier</strong><small>releve-juillet.xlsx</small></div>
            <div><span>2</span><strong>Vérifier le mapping</strong><small>Suggestions intelligentes</small></div>
            <div><span>3</span><strong>Confirmer l'import</strong><small>Aucune écriture pour ce prototype</small></div>
          </div>
          <div className="mapping-grid" aria-label="Exemple d'association de colonnes">
            <div><span>Date opération</span><Icon name="arrow" size={17} /><strong>Date comptable</strong><Badge tone="mint">98 %</Badge></div>
            <div><span>Libellé</span><Icon name="arrow" size={17} /><strong>Description</strong><Badge tone="mint">96 %</Badge></div>
            <div><span>Débit</span><Icon name="arrow" size={17} /><strong>Montant + sens</strong><Badge tone="gold">À vérifier</Badge></div>
          </div>
          <InlineNotice>Cette prévisualisation utilise un nom de fichier fictif et n'envoie aucun document.</InlineNotice>
        </Card>
      )}

      <Card className="transaction-toolbar">
        <label className="search-field">
          <span className="sr-only">Rechercher une transaction</span>
          <Icon name="search" size={20} />
          <input
            type="search"
            value={search}
            onChange={(event) => setSearch(event.target.value)}
            placeholder="Rechercher un commerçant ou une catégorie"
          />
        </label>
        <div className="segmented-control" aria-label="Filtrer les transactions">
          {filters.map((item) => (
            <button
              key={item.id}
              type="button"
              aria-pressed={filter === item.id}
              className={filter === item.id ? 'is-active' : ''}
              onClick={() => setFilter(item.id)}
            >
              {item.label}
            </button>
          ))}
        </div>
        <label className="select-field">
          <span className="sr-only">Choisir un compte</span>
          <select defaultValue="all"><option value="all">Tous les comptes</option><option>Compte courant</option><option>Compte épargne</option></select>
        </label>
      </Card>

      <section aria-live="polite" aria-label="Liste des transactions" className="transaction-list">
        <div className="transaction-list__summary">
          <p><strong>{visibleTransactions.length} transaction{visibleTransactions.length > 1 ? 's' : ''}</strong> affichée{visibleTransactions.length > 1 ? 's' : ''}</p>
          <p>Juillet 2026</p>
        </div>
        {visibleTransactions.length === 0 ? (
          <Card className="empty-state"><Icon name="search" /><h2>Aucun résultat</h2><p>Essayez un autre mot ou choisissez le filtre « Toutes ».</p></Card>
        ) : visibleTransactions.map((transaction) => (
          <article className="transaction-row" key={transaction.id}>
            <span className={`transaction-row__icon category-dot--${transaction.categoryTone}`} aria-hidden="true">
              {transaction.direction === 'CREDIT' ? '↙' : '↗'}
            </span>
            <div className="transaction-row__main">
              <h2>{transaction.merchant}</h2>
              <p>{transaction.date} · {transaction.account}</p>
            </div>
            <Badge tone={transaction.categoryTone}>{transaction.category}</Badge>
            <div className="transaction-row__amount">
              <strong className={transaction.direction === 'CREDIT' ? 'amount-credit' : 'amount-debit'}>
                {transaction.direction === 'CREDIT' ? '+' : '−'} {formatTnd(transaction.amount)}
              </strong>
              <small>{transaction.direction === 'CREDIT' ? 'Revenu' : 'Dépense'}</small>
            </div>
          </article>
        ))}
      </section>
    </div>
  )
}
