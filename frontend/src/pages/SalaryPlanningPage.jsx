import Icon from '../components/Icon'
import { Badge, Card, formatTnd, InlineNotice, MetricCard, PageHeader, ProgressBar } from '../components/Ui'
import { salaryPlan } from '../data/demoData'

export default function SalaryPlanningPage() {
  const spent = salaryPlan.fixedExpenses + salaryPlan.variableExpenses
  const spentPercentage = (spent / salaryPlan.salary) * 100

  return (
    <div className="page">
      <PageHeader
        eyebrow={salaryPlan.month}
        title="Planifier mon salaire"
        description="Donnez une place à chaque dinar, sans compliquer votre quotidien."
        action={<button className="button button--primary" type="button">Ajuster mon plan</button>}
      />

      <section className="metrics-grid metrics-grid--planning" aria-label="Résumé du plan mensuel">
        <MetricCard icon="wallet" label="Salaire mensuel" value={formatTnd(salaryPlan.salary)} note="Revenu disponible" tone="teal" />
        <MetricCard icon="plan" label="Charges fixes" value={formatTnd(salaryPlan.fixedExpenses)} note="Loyer, factures et abonnements" tone="peach" />
        <MetricCard icon="transactions" label="Dépenses variables" value={formatTnd(salaryPlan.variableExpenses)} note="Alimentation, transport et loisirs" tone="gold" />
        <MetricCard icon="target" label="Capacité d'épargne" value={formatTnd(salaryPlan.savingsCapacity)} note="Disponible pour vos objectifs" tone="mint" />
      </section>

      <div className="planning-grid">
        <Card className="budget-balance-card">
          <div className="card-heading">
            <div><p className="eyebrow">Votre mois en un coup d'œil</p><h2>Un budget qui reste positif</h2></div>
            <Badge tone="teal">{Math.round(100 - spentPercentage)} % disponibles</Badge>
          </div>
          <div className="budget-scale" aria-label="Répartition de votre salaire">
            <span className="budget-scale__fixed" style={{ width: `${(salaryPlan.fixedExpenses / salaryPlan.salary) * 100}%` }} />
            <span className="budget-scale__variable" style={{ width: `${(salaryPlan.variableExpenses / salaryPlan.salary) * 100}%` }} />
            <span className="budget-scale__savings" style={{ width: `${(salaryPlan.savingsCapacity / salaryPlan.salary) * 100}%` }} />
          </div>
          <div className="budget-legend">
            <span><i className="legend-dot legend-dot--fixed" /> Charges fixes <strong>{formatTnd(salaryPlan.fixedExpenses)}</strong></span>
            <span><i className="legend-dot legend-dot--variable" /> Dépenses variables <strong>{formatTnd(salaryPlan.variableExpenses)}</strong></span>
            <span><i className="legend-dot legend-dot--savings" /> Épargne possible <strong>{formatTnd(salaryPlan.savingsCapacity)}</strong></span>
          </div>
          <InlineNotice tone="success" icon="check">
            Votre capacité d'épargne reste confortable. Conservez au moins 300 TND de marge pour les imprévus.
          </InlineNotice>
        </Card>

        <Card className="payday-card">
          <span className="payday-card__icon"><Icon name="wallet" size={30} /></span>
          <p className="eyebrow">Prochain salaire estimé</p>
          <h2>1er août 2026</h2>
          <p>Dans 10 jours</p>
          <hr />
          <p className="muted">Solde prévu juste avant</p>
          <strong>{formatTnd(1680)}</strong>
        </Card>
      </div>

      <section className="section-block" aria-labelledby="recommended-allocation-title">
        <div className="section-heading">
          <div><p className="eyebrow">Repère simple</p><h2 id="recommended-allocation-title">Répartition budgétaire recommandée</h2></div>
          <Badge tone="blue">Guide 50 / 30 / 20</Badge>
        </div>
        <div className="allocation-grid">
          {salaryPlan.allocations.map((allocation) => (
            <Card className="allocation-card" key={allocation.label}>
              <div><span className={`allocation-card__percentage allocation-card__percentage--${allocation.tone}`}>{allocation.percentage} %</span><Badge tone={allocation.tone}>{formatTnd(allocation.amount)}</Badge></div>
              <h3>{allocation.label}</h3>
              <ProgressBar label={`${allocation.label} : ${allocation.percentage} %`} tone={allocation.tone} value={allocation.percentage} />
              <p>{allocation.percentage === 50 ? 'Logement, factures, santé et transport essentiel.' : allocation.percentage === 30 ? 'Sorties, achats personnels et plaisirs du quotidien.' : 'Épargne de sécurité et projets qui vous tiennent à cœur.'}</p>
            </Card>
          ))}
        </div>
      </section>
    </div>
  )
}
