import { Link } from 'react-router-dom'
import ForecastChart from '../components/ForecastChart'
import Icon from '../components/Icon'
import { Badge, Card, formatTnd, MetricCard, PageHeader, ProgressBar } from '../components/Ui'
import {
  forecast,
  goals,
  profile,
  recommendations,
  summary,
  transactions,
  wellnessDetails,
} from '../data/demoData'

const recommendationPaths = ['/previsions', '/objectifs', '/transactions']

export default function DashboardPage() {
  return (
    <div className="page">
      <PageHeader
        eyebrow="Mercredi 22 juillet 2026"
        title={`Bonjour ${profile.firstName}, comment allez-vous ?`}
        description="Voici l'essentiel de votre situation, expliqué simplement."
        action={<Link className="button button--secondary" to="/transactions">Voir mes transactions</Link>}
      />

      <section className="metrics-grid" aria-label="Résumé financier du mois">
        <MetricCard
          icon="wallet"
          label="Solde global"
          value={formatTnd(summary.globalBalance)}
          note="Sur vos 2 comptes"
          tone="teal"
        />
        <MetricCard
          icon="transactions"
          label="Revenus ce mois"
          value={formatTnd(summary.monthlyIncome)}
          note="Salaire reçu le 1er juillet"
          tone="mint"
        />
        <MetricCard
          icon="plan"
          label="Dépenses ce mois"
          value={formatTnd(summary.monthlyExpenses)}
          note="65 % de vos revenus"
          tone="peach"
        />
        <MetricCard
          icon="target"
          label="Capacité d'épargne"
          value={formatTnd(summary.savingsCapacity)}
          note="Montant encore disponible"
          tone="gold"
        />
      </section>

      <div className="dashboard-grid">
        <Card className="wellness-card">
          <div className="card-heading">
            <div>
              <p className="eyebrow">Votre équilibre financier</p>
              <h2>Un score encourageant</h2>
            </div>
            <Badge tone="teal">En bonne voie</Badge>
          </div>
          <div className="wellness-card__body">
            <div className="score-ring" style={{ '--score': `${summary.wellnessScore * 3.6}deg` }}>
              <div><strong>{summary.wellnessScore}</strong><span>sur 100</span></div>
            </div>
            <div className="wellness-card__details">
              <p>
                Votre budget est équilibré. Le prochain pas utile est de renforcer
                doucement votre épargne de précaution.
              </p>
              {wellnessDetails.map((detail) => (
                <div className="score-detail" key={detail.label}>
                  <span><strong>{detail.label}</strong><small>{detail.note}</small></span>
                  <strong>{detail.score}/100</strong>
                </div>
              ))}
            </div>
          </div>
          <p className="disclaimer"><Icon name="info" size={17} /> Ce score est informatif et ne constitue pas un conseil financier garanti.</p>
        </Card>

        <Card className="forecast-card">
          <div className="card-heading">
            <div>
              <p className="eyebrow">Prévision de fin de mois</p>
              <h2>{formatTnd(summary.forecastEndBalance)}</h2>
            </div>
            <Badge tone="blue">Confiance {forecast.confidence} %</Badge>
          </div>
          <ForecastChart compact labels={forecast.labels} values={forecast.values} />
          <p className="forecast-card__message">
            Vous devriez terminer juillet avec un solde positif, même après vos dépenses habituelles.
          </p>
          <Link className="text-link" to="/previsions">Comprendre cette prévision <Icon name="arrow" size={17} /></Link>
        </Card>
      </div>

      <section className="section-block" aria-labelledby="goals-title">
        <div className="section-heading">
          <div><p className="eyebrow">Vos projets</p><h2 id="goals-title">Vos objectifs avancent</h2></div>
          <Link className="text-link" to="/objectifs">Voir tous les objectifs <Icon name="arrow" size={17} /></Link>
        </div>
        <div className="goal-grid">
          {goals.map((goal) => {
            const percentage = (goal.current / goal.target) * 100
            return (
              <Card className="goal-card" key={goal.id}>
                <div className={`goal-card__icon goal-card__icon--${goal.tone}`} aria-hidden="true">
                  {goal.icon === 'umbrella' ? '☂' : goal.icon === 'sun' ? '☀' : '▰'}
                </div>
                <div className="goal-card__title"><h3>{goal.name}</h3><strong>{Math.round(percentage)} %</strong></div>
                <ProgressBar label={`Progression de ${goal.name}`} tone={goal.tone} value={percentage} />
                <p><strong>{formatTnd(goal.current)}</strong> sur {formatTnd(goal.target)}</p>
                <small>{formatTnd(goal.monthlyAllocation)} prévus chaque mois</small>
              </Card>
            )
          })}
        </div>
      </section>

      <div className="dashboard-grid dashboard-grid--lower">
        <Card>
          <div className="card-heading">
            <div><p className="eyebrow">À retenir</p><h2>Conseils personnalisés</h2></div>
            <span className="soft-icon"><Icon name="shield" /></span>
          </div>
          <div className="recommendation-list">
            {recommendations.map((recommendation, index) => (
              <article className="recommendation" key={recommendation.id}>
                <span className={`recommendation__marker recommendation__marker--${recommendation.tone}`} />
                <div>
                  <h3>{recommendation.title}</h3>
                  <p>{recommendation.message}</p>
                  <Link className="text-link" to={recommendationPaths[index]}>{recommendation.action}</Link>
                </div>
              </article>
            ))}
          </div>
        </Card>

        <Card>
          <div className="card-heading">
            <div><p className="eyebrow">Mouvements récents</p><h2>Dernières transactions</h2></div>
            <Link className="icon-button" to="/transactions" aria-label="Voir toutes les transactions"><Icon name="arrow" /></Link>
          </div>
          <div className="compact-transactions">
            {transactions.slice(0, 4).map((transaction) => (
              <div className="compact-transaction" key={transaction.id}>
                <span className={`category-dot category-dot--${transaction.categoryTone}`} aria-hidden="true" />
                <span><strong>{transaction.merchant}</strong><small>{transaction.date} · {transaction.category}</small></span>
                <strong className={transaction.direction === 'CREDIT' ? 'amount-credit' : 'amount-debit'}>
                  {transaction.direction === 'CREDIT' ? '+' : '−'} {formatTnd(transaction.amount)}
                </strong>
              </div>
            ))}
          </div>
        </Card>
      </div>
    </div>
  )
}
