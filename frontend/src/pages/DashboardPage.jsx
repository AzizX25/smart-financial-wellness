import { useEffect, useMemo, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import Icon from '../components/Icon'
import RecommendationsSection from '../components/RecommendationsSection'
import { Badge, Card, formatCurrency, InlineNotice, MetricCard, PageHeader } from '../components/Ui'
import { useFinancialData } from '../financial/FinancialDataContext'
import { calculateCurrentFinancialScore } from '../services/financialScoreApi'

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

const factorLabels = {
  BALANCE_RESILIENCE: 'Résilience du solde',
  ESSENTIAL_EXPENSE_BURDEN: 'Poids des dépenses essentielles',
  GOAL_PROGRESS: 'Progression des objectifs',
  INCOME_STABILITY: 'Stabilité des revenus',
  SALARY_PLAN_ADHERENCE: 'Respect du budget prévu',
  SAVINGS_RATE: "Taux d'épargne",
}

const emptyList = Object.freeze([])

// Presentation follows the server's authoritative score band, never a local recalculation.
const scoreBandTones = {
  A_RENFORCER: 'red',
  FRAGILE: 'orange',
  EN_PROGRESSION: 'yellow',
  SOLIDE: 'green',
}

const scoreInterpretations = {
  A_RENFORCER: 'Les facteurs évalués indiquent plusieurs points à renforcer. Consultez leur détail pour comprendre ce qui pèse sur votre résultat et avancer progressivement.',
  FRAGILE: 'Votre indicateur reste fragile : certains repères financiers méritent votre attention. Le détail des facteurs vous aide à identifier les points à consolider.',
  EN_PROGRESSION: 'Votre indicateur est encourageant, avec des points encore à consolider. Regardez les facteurs les moins élevés pour comprendre où une amélioration est possible.',
  SOLIDE: 'Les facteurs évalués donnent un indicateur solide. Gardez un suivi régulier : ce résultat décrit la période analysée et peut évoluer avec votre situation.',
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
    dataVersion,
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
  const balanceTotals = dashboardSummary?.balanceTotals || emptyList
  const cashFlows = dashboardSummary?.monthlyCashFlows || emptyList
  const singleCurrencyFlow = balanceTotals.length === 1
    ? cashFlows.find((flow) => flow.currency === balanceTotals[0].currency)
    : null
  const currencies = useMemo(() => [...new Set([
    ...balanceTotals.map((total) => total.currency),
    ...cashFlows.map((flow) => flow.currency),
  ])].sort(), [balanceTotals, cashFlows])
  const [scoreCurrency, setScoreCurrency] = useState('')
  const [financialScore, setFinancialScore] = useState(null)
  const [scoreError, setScoreError] = useState('')
  const [scoreLoading, setScoreLoading] = useState(false)
  const scoreRequest = useRef(0)
  const displayedScore = financialScore?.currency === scoreCurrency ? financialScore : null
  const today = new Intl.DateTimeFormat('fr-TN', { dateStyle: 'full' }).format(new Date())

  useEffect(() => {
    if (currencies.length === 0) {
      setScoreCurrency('')
      setFinancialScore(null)
      return
    }
    setScoreCurrency((current) => currencies.includes(current) ? current : currencies[0])
  }, [currencies])

  useEffect(() => {
    if (!scoreCurrency) return undefined
    const requestId = ++scoreRequest.current
    setScoreLoading(true)
    setScoreError('')
    calculateCurrentFinancialScore(scoreCurrency)
      .then((response) => {
        if (requestId === scoreRequest.current) setFinancialScore(response)
      })
      .catch(() => {
        if (requestId === scoreRequest.current) {
          setScoreError("L'indicateur de bien-être financier n'a pas pu être calculé.")
        }
      })
      .finally(() => {
        if (requestId === scoreRequest.current) setScoreLoading(false)
      })
    return () => {
      if (requestId === scoreRequest.current) scoreRequest.current += 1
    }
  }, [dataVersion, scoreCurrency])

  return (
    <div className="page dashboard-page">
      <PageHeader
        eyebrow={today}
        title={greetingName ? `Bonjour ${greetingName}` : 'Bonjour'}
        description="Retrouvez vos soldes, vos opérations bancaires et vos relevés importés dans un même espace."
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

      {(accounts.some((account) => account.accountOrigin !== 'MANUAL') || synchronizationStatus) && (
        <Card className="synchronization-strip">
          <div><span className="soft-icon"><Icon name="shield" /></span><div><p className="eyebrow">Synchronisation bancaire</p><strong>{syncLabels[synchronizationStatus?.status] || synchronizationStatus?.status || 'Non disponible'}</strong></div></div>
          <div><span>Dernier succès</span><strong>{formatDate(synchronizationStatus?.lastSuccessfulAt)}</strong></div>
          {synchronizationStatus?.lastFailureCode && <Badge tone="rose">{synchronizationStatus.lastFailureCode}</Badge>}
        </Card>
      )}

      {balanceTotals.length === 1 && (
        <section className="metrics-grid metrics-grid--three" aria-label="Résumé financier du mois">
          <MetricCard icon="wallet" label="Solde total" value={formatCurrency(balanceTotals[0].totalBalance, balanceTotals[0].currency)} note={`${balanceTotals[0].accountCount} compte${balanceTotals[0].accountCount === 1 ? '' : 's'} dans la même devise`} tone="navy" />
          <MetricCard icon="transactions" label="Revenus ce mois" value={formatCurrency(singleCurrencyFlow?.income || 0, balanceTotals[0].currency)} note="Crédits hors transferts reconnus" tone="mint" />
          <MetricCard icon="plan" label="Dépenses ce mois" value={formatCurrency(singleCurrencyFlow?.expenses || 0, balanceTotals[0].currency)} note="Débits hors transferts reconnus" tone="peach" />
          <MetricCard icon="target" label="Flux net ce mois" value={formatCurrency(singleCurrencyFlow?.netCashFlow || 0, balanceTotals[0].currency)} note="Revenus moins dépenses" tone={singleCurrencyFlow?.netCashFlow < 0 ? 'peach' : singleCurrencyFlow?.netCashFlow > 0 ? 'mint' : 'blue'} />
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

      <RecommendationsSection currency={scoreCurrency} dataVersion={dataVersion} />

      <section className="section-block" aria-labelledby="financial-score-title">
        <div className="section-heading">
          <div><p className="eyebrow">Indicateur pédagogique</p><h2 id="financial-score-title">Bien-être financier</h2></div>
          {currencies.length > 1 && (
            <label className="score-currency-control">
              <span>Devise analysée</span>
              <select value={scoreCurrency} onChange={(event) => setScoreCurrency(event.target.value)}>
                {currencies.map((currency) => <option key={currency} value={currency}>{currency}</option>)}
              </select>
            </label>
          )}
        </div>

        {currencies.length === 0 && !loading && (
          <Card className="empty-state empty-state--compact">
            <Icon name="wallet" />
            <h3>Aucune devise à analyser</h3>
            <p>Synchronisez ou ajoutez des données financières pour calculer cet indicateur.</p>
          </Card>
        )}
        {scoreLoading && !displayedScore && (
          <Card className="bank-state" aria-live="polite"><span className="auth-spinner" /> Calcul de votre indicateur.</Card>
        )}
        {scoreError && (
          <InlineNotice live tone="warning" icon="alert">
            {scoreError} {displayedScore && 'Le dernier résultat affiché est conservé.'}
          </InlineNotice>
        )}
        {displayedScore && scoreLoading && (
          <p className="score-refresh-state" role="status">Actualisation des données en cours.</p>
        )}
        {displayedScore && !displayedScore.available && (
          <Card className="wellness-card">
            <div className="card-heading"><div><p className="eyebrow">Données insuffisantes</p><h3>Score non disponible</h3></div><Badge tone="neutral">{displayedScore.currency}</Badge></div>
            <p>Trois mois terminés avec des revenus positifs et au moins 50 % des facteurs disponibles sont nécessaires.</p>
            <div className="score-factor-list">
              {displayedScore.factors.filter((factor) => !factor.available).map((factor) => (
                <div className="score-detail" key={factor.code}>
                  <span><strong>{factorLabels[factor.code] || factor.code}</strong><small>{factor.explanation}</small></span>
                  <Badge tone="neutral">Non disponible</Badge>
                </div>
              ))}
            </div>
            <p className="disclaimer"><Icon name="info" />{displayedScore.disclaimer}</p>
          </Card>
        )}
        {displayedScore?.available && (
          <Card className="wellness-card">
            <div className="card-heading">
              <div><p className="eyebrow">Calculé le {formatDate(`${displayedScore.asOfDate}T00:00:00`, { dateStyle: 'long' })}</p><h3>{displayedScore.bandLabel}</h3></div>
              <Badge tone="teal">{displayedScore.currency}</Badge>
            </div>
            <div className="wellness-card__body">
              <div className={`score-ring score-ring--${scoreBandTones[displayedScore.bandCode] || 'neutral'}`} style={{ '--score': `${displayedScore.score}%` }} role="img" aria-label={`Score de bien-être financier ${displayedScore.score} sur 100, niveau ${displayedScore.bandLabel}`}>
                <div><strong>{Number(displayedScore.score).toLocaleString('fr-TN', { minimumFractionDigits: 1, maximumFractionDigits: 1 })}</strong><span>sur 100</span></div>
              </div>
              <div className="wellness-card__details">
                <p>{displayedScore.bandDescription}</p>
                <div className="score-interpretation" role="note" aria-label="Interprétation du score">
                  <h4>Ce que signifie votre score</h4>
                  <p>{scoreInterpretations[displayedScore.bandCode] || 'Le niveau de ce score n’est pas reconnu. Consultez le détail des facteurs disponibles pour comprendre le résultat.'}</p>
                  <p>Cette lecture porte uniquement sur les facteurs évalués. Les facteurs indisponibles ne sont pas comptés comme zéro.</p>
                </div>
                <p>Données du {formatDate(`${displayedScore.historyStart}T00:00:00`, { dateStyle: 'medium' })} au {formatDate(`${displayedScore.historyEnd}T00:00:00`, { dateStyle: 'medium' })}. Couverture : {displayedScore.availableBaseWeight} %.</p>
              </div>
            </div>
            <details className="score-explanations">
              <summary>Comprendre les facteurs du score</summary>
              <div className="score-factor-list" aria-label="Détail des facteurs">
                {displayedScore.factors.map((factor) => (
                  <div className="score-detail" key={factor.code}>
                    <span>
                      <strong>{factorLabels[factor.code] || factor.code}</strong>
                      <small>{factor.explanation}</small>
                    </span>
                    {factor.available
                      ? <span className="score-factor-value"><strong>{Number(factor.score).toLocaleString('fr-TN', { minimumFractionDigits: 1, maximumFractionDigits: 1 })}/100</strong><small>{factor.score >= 60 ? 'Niveau relatif plus élevé' : 'Niveau relatif plus faible'}</small></span>
                      : <Badge tone="neutral">Non disponible</Badge>}
                  </div>
                ))}
              </div>
            </details>
            <p className="disclaimer"><Icon name="info" />{displayedScore.disclaimer}</p>
          </Card>
        )}
      </section>

      {accounts.length > 0 && (
        <section className="section-block" aria-labelledby="accounts-title">
          <div className="section-heading"><div><p className="eyebrow">Vos comptes</p><h2 id="accounts-title">Mes soldes</h2></div><Badge tone={connected ? 'teal' : 'neutral'}>{connected ? 'Actualisables' : historical ? 'Historiques' : 'Soldes déclarés'}</Badge></div>
          <div className="account-card-grid">
            {accounts.map((account) => (
              <Card className="dashboard-account-card" key={account.id}>
                <div><p className="eyebrow">{accountTypes[account.accountType] || account.accountType}</p><h3>{account.displayName}</h3><span>{account.maskedAccountReference}</span></div>
                <strong>{formatCurrency(account.currentBalance, account.currency)}</strong>
                <small>{account.accountOrigin === 'MANUAL' ? 'Solde déclaré par vous, non recalculé par les imports' : `Mis à jour ${formatDate(account.lastSynchronizedAt, { dateStyle: 'medium' })}`}</small>
              </Card>
            ))}
          </div>
        </section>
      )}

      <Card className="recent-local-transactions">
        <div className="card-heading"><div><p className="eyebrow">Dernières opérations</p><h2>Transactions récentes</h2></div><Link className="icon-button" to="/transactions" aria-label="Voir toutes les transactions"><Icon name="arrow" /></Link></div>
        {recentTransactions.length === 0 ? (
            <div className="empty-state empty-state--compact"><Icon name="transactions" /><h3>Aucune transaction</h3><p>Vos opérations bancaires ou importées apparaîtront ici.</p></div>
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
