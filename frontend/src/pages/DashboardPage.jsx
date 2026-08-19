import { Link } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import Icon from '../components/Icon'
import { Badge, Card, formatCurrency, InlineNotice, MetricCard, PageHeader } from '../components/Ui'
import { useFinancialData } from '../financial/FinancialDataContext'

const accountTypes = {
  CASH: 'Espèces',
  CHECKING: 'Compte courant',
  OTHER: 'Autre compte',
  SAVINGS: 'Compte d’épargne',
}

const syncLabels = {
  FAILED: 'Échec — historique conservé',
  IDLE: 'Jamais synchronisé',
  RUNNING: 'Synchronisation en cours',
  SUCCEEDED: 'À jour',
}

function formatDate(value, options = { dateStyle: 'long', timeStyle: 'short' }) {
  if (!value) return 'Jamais'
  const date = new Date(value)
  return Number.isNaN(date.getTime())
    ? 'Non disponible'
    : new Intl.DateTimeFormat('fr-TN', options).format(date)
}

export default function DashboardPage() {
  const { user } = useAuth()
  const {
    accounts,
    connected,
    dashboardSummary,
    error,
    historical,
    loading,
    recentTransactions,
    synchronizationError,
    synchronizationStatus,
    synchronize,
    synchronizing,
  } = useFinancialData()
  const greetingName = user?.firstName?.trim() || user?.displayName?.trim()
  const accountNames = new Map(accounts.map((account) => [account.id, account.displayName]))
  const balanceTotals = dashboardSummary?.balanceTotals || []
  const cashFlows = dashboardSummary?.monthlyCashFlows || []
  const singleCurrencyFlow = balanceTotals.length === 1
    ? cashFlows.find((flow) => flow.currency === balanceTotals[0].currency)
    : null
  const currencies = [...new Set([
    ...balanceTotals.map((total) => total.currency),
    ...cashFlows.map((flow) => flow.currency),
  ])].sort()
  const today = new Intl.DateTimeFormat('fr-TN', { dateStyle: 'full' }).format(new Date())

  return (
    <div className="page">
      <PageHeader
        eyebrow={today}
        title={greetingName ? `Bonjour ${greetingName}` : 'Bonjour'}
        description="Retrouvez vos comptes et transactions synchronisés, sans estimation ni valeur fictive."
        action={(
          <button className="button button--primary" disabled={!connected || synchronizing} onClick={synchronize} type="button">
            <Icon name="transactions" size={18} />
            {synchronizing ? 'Synchronisation en cours…' : synchronizationStatus?.lastSuccessfulAt ? 'Actualiser mes données' : 'Synchroniser mes données'}
          </button>
        )}
      />

      {loading && accounts.length === 0 && <Card className="bank-state" aria-live="polite"><span className="auth-spinner" /> Chargement de vos données financières…</Card>}
      {historical && <InlineNotice tone="warning" icon="info">Ces données sont historiques. Reconnectez votre banque pour les actualiser.</InlineNotice>}
      {!connected && accounts.length === 0 && !loading && <InlineNotice tone="info" icon="bank">Aucune banque connectée et aucun historique disponible. <Link className="text-link" to="/connexion-bancaire">Connecter une banque</Link></InlineNotice>}
      {connected && accounts.length === 0 && !synchronizationStatus?.lastSuccessfulAt && !loading && <InlineNotice tone="info" icon="transactions">La banque est connectée, mais aucune synchronisation n’a encore été effectuée.</InlineNotice>}
      {(error || synchronizationError) && <InlineNotice live tone="warning" icon="alert">{synchronizationError || error} {accounts.length > 0 && 'Les dernières données disponibles restent affichées.'}</InlineNotice>}

      {(accounts.length > 0 || synchronizationStatus) && (
        <Card className="synchronization-strip">
          <div><span className="soft-icon"><Icon name="shield" /></span><div><p className="eyebrow">Synchronisation bancaire</p><strong>{syncLabels[synchronizationStatus?.status] || synchronizationStatus?.status || 'Non disponible'}</strong></div></div>
          <div><span>Dernier succès</span><strong>{formatDate(synchronizationStatus?.lastSuccessfulAt)}</strong></div>
          {synchronizationStatus?.lastFailureCode && <Badge tone="rose">{synchronizationStatus.lastFailureCode}</Badge>}
        </Card>
      )}

      {balanceTotals.length === 1 && (
        <section className="metrics-grid metrics-grid--three" aria-label="Résumé financier du mois">
          <MetricCard icon="wallet" label="Solde total" value={formatCurrency(balanceTotals[0].totalBalance, balanceTotals[0].currency)} note={`${balanceTotals[0].accountCount} compte${balanceTotals[0].accountCount === 1 ? '' : 's'} dans la même devise`} tone="teal" />
          <MetricCard icon="transactions" label="Revenus ce mois" value={formatCurrency(singleCurrencyFlow?.income || 0, balanceTotals[0].currency)} note="Crédits hors transferts reconnus" tone="mint" />
          <MetricCard icon="plan" label="Dépenses ce mois" value={formatCurrency(singleCurrencyFlow?.expenses || 0, balanceTotals[0].currency)} note="Débits hors transferts reconnus" tone="peach" />
          <MetricCard icon="target" label="Flux net ce mois" value={formatCurrency(singleCurrencyFlow?.netCashFlow || 0, balanceTotals[0].currency)} note="Revenus moins dépenses" tone="gold" />
        </section>
      )}

      {balanceTotals.length > 1 && (
        <section className="section-block" aria-labelledby="currency-summary-title">
          <div className="section-heading"><div><p className="eyebrow">Sans conversion inventée</p><h2 id="currency-summary-title">Résumé par devise</h2></div></div>
          <div className="currency-summary-grid">
            {currencies.map((currency) => {
              const balance = balanceTotals.find((total) => total.currency === currency)
              const flow = cashFlows.find((item) => item.currency === currency)
              return (
                <Card className="currency-summary" key={currency}>
                  <div className="card-heading"><div><p className="eyebrow">Devise</p><h3>{currency}</h3></div><Badge tone="blue">{balance?.accountCount || 0} compte{balance?.accountCount === 1 ? '' : 's'}</Badge></div>
                  <dl className="financial-definition-list">
                    <div><dt>Solde</dt><dd>{formatCurrency(balance?.totalBalance || 0, currency)}</dd></div>
                    <div><dt>Revenus du mois</dt><dd>{formatCurrency(flow?.income || 0, currency)}</dd></div>
                    <div><dt>Dépenses du mois</dt><dd>{formatCurrency(flow?.expenses || 0, currency)}</dd></div>
                    <div><dt>Flux net</dt><dd>{formatCurrency(flow?.netCashFlow || 0, currency)}</dd></div>
                  </dl>
                </Card>
              )
            })}
          </div>
        </section>
      )}

      {accounts.length > 0 && (
        <section className="section-block" aria-labelledby="accounts-title">
          <div className="section-heading"><div><p className="eyebrow">Comptes synchronisés</p><h2 id="accounts-title">Mes soldes</h2></div><Badge tone={connected ? 'teal' : 'neutral'}>{connected ? 'Actualisables' : 'Historiques'}</Badge></div>
          <div className="account-card-grid">
            {accounts.map((account) => (
              <Card className="dashboard-account-card" key={account.id}>
                <div><p className="eyebrow">{accountTypes[account.accountType] || account.accountType}</p><h3>{account.displayName}</h3><span>{account.maskedAccountReference}</span></div>
                <strong>{formatCurrency(account.currentBalance, account.currency)}</strong>
                <small>Mis à jour {formatDate(account.lastSynchronizedAt, { dateStyle: 'medium' })}</small>
              </Card>
            ))}
          </div>
        </section>
      )}

      <Card className="recent-local-transactions">
        <div className="card-heading"><div><p className="eyebrow">Mouvements synchronisés</p><h2>Transactions récentes</h2></div><Link className="icon-button" to="/transactions" aria-label="Voir toutes les transactions"><Icon name="arrow" /></Link></div>
        {recentTransactions.length === 0 ? (
          <div className="empty-state empty-state--compact"><Icon name="transactions" /><h3>Aucune transaction</h3><p>Les prochaines opérations synchronisées apparaîtront ici.</p></div>
        ) : (
          <div className="compact-transactions">
            {recentTransactions.map((transaction) => (
              <div className="compact-transaction" key={transaction.id}>
                <span className={`transaction-row__icon transaction-row__icon--${transaction.direction === 'CREDIT' ? 'income' : 'expense'}`} aria-hidden="true">{transaction.direction === 'CREDIT' ? '↓' : '↑'}</span>
                <span><strong>{transaction.description || 'Opération sans libellé'}</strong><small>{formatDate(`${transaction.bookingDate}T00:00:00`, { dateStyle: 'medium' })} · {accountNames.get(transaction.accountId) || 'Compte synchronisé'}</small></span>
                <strong className={transaction.direction === 'CREDIT' ? 'amount-credit' : 'amount-debit'}>{transaction.direction === 'CREDIT' ? '+' : '−'} {formatCurrency(transaction.amount, transaction.currency)}</strong>
              </div>
            ))}
          </div>
        )}
      </Card>
    </div>
  )
}
