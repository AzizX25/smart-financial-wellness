import { useMemo, useState } from 'react'
import Icon from '../components/Icon'
import { Badge, Card, formatTnd, InlineNotice, PageHeader, ProgressBar } from '../components/Ui'
import { goalScenarios, goals } from '../data/demoData'

export default function GoalsPage() {
  const [monthlyContribution, setMonthlyContribution] = useState(250)
  const simulatedGoal = goals[1]
  const simulation = useMemo(() => {
    const remaining = simulatedGoal.target - simulatedGoal.current
    const months = Math.max(1, Math.ceil(remaining / monthlyContribution))
    const date = new Date(2026, 6 + months, 1)
    return {
      months,
      targetDate: date.toLocaleDateString('fr-FR', { month: 'long', year: 'numeric' }),
    }
  }, [monthlyContribution, simulatedGoal.current, simulatedGoal.target])

  return (
    <div className="page">
      <PageHeader
        eyebrow="Vos projets de vie"
        title="Mes objectifs"
        description="Visualisez vos progrès et explorez plusieurs chemins, sans changer votre vrai plan."
        action={<button className="button button--primary" type="button">Créer un objectif</button>}
      />

      <InlineNotice tone="success" icon="target">
        Votre Salary Planning réserve actuellement <strong>{formatTnd(730)}</strong> par mois à vos trois objectifs.
      </InlineNotice>

      <section className="goal-grid goal-grid--detailed" aria-label="Liste des objectifs">
        {goals.map((goal) => {
          const percentage = (goal.current / goal.target) * 100
          return (
            <Card className="goal-card goal-card--detailed" key={goal.id}>
              <div className="goal-card__top">
                <span className={`goal-card__icon goal-card__icon--${goal.tone}`} aria-hidden="true">
                  {goal.icon === 'umbrella' ? '☂' : goal.icon === 'sun' ? '☀' : '▰'}
                </span>
                <Badge tone={goal.tone}>{goal.targetDate}</Badge>
              </div>
              <h2>{goal.name}</h2>
              <div className="goal-card__amounts"><strong>{formatTnd(goal.current)}</strong><span>sur {formatTnd(goal.target)}</span></div>
              <ProgressBar label={`Progression de ${goal.name}`} tone={goal.tone} value={percentage} />
              <div className="goal-card__allocation"><Icon name="plan" size={18} /><span>Allocation mensuelle</span><strong>{formatTnd(goal.monthlyAllocation)}</strong></div>
            </Card>
          )
        })}
      </section>

      <div className="simulator-grid">
        <Card className="goal-simulator">
          <p className="eyebrow">Simulateur</p>
          <h2>Et si j'épargnais un autre montant ?</h2>
          <p className="muted">Testez une contribution pour « Vacances à Tabarka ». Cette simulation ne modifie aucune contribution réelle.</p>

          <label className="range-field">
            <span><strong>Contribution mensuelle</strong><output>{formatTnd(monthlyContribution)}</output></span>
            <input
              type="range"
              min="100"
              max="500"
              step="25"
              value={monthlyContribution}
              onChange={(event) => setMonthlyContribution(Number(event.target.value))}
            />
            <small><span>100 TND</span><span>500 TND</span></small>
          </label>

          <div className="simulation-result">
            <span className="soft-icon"><Icon name="target" /></span>
            <div><p>Date d'atteinte estimée</p><h3>{simulation.targetDate}</h3><small>Dans environ {simulation.months} mois</small></div>
          </div>
        </Card>

        <Card className="scenario-card">
          <div className="card-heading"><div><p className="eyebrow">Scénarios enregistrés</p><h2>Comparer les chemins</h2></div><Badge tone="blue">3 options</Badge></div>
          <div className="scenario-table" role="table" aria-label="Comparaison des scénarios d'épargne">
            <div className="scenario-table__header" role="row"><span>Scénario</span><span>Par mois</span><span>Date estimée</span><span>Effort</span></div>
            {goalScenarios.map((scenario) => (
              <div className={scenario.name === 'Équilibré' ? 'is-recommended' : ''} role="row" key={scenario.name}>
                <span><strong>{scenario.name}</strong>{scenario.name === 'Équilibré' && <small>Recommandé</small>}</span>
                <span>{formatTnd(scenario.monthly)}</span>
                <span>{scenario.targetDate}</span>
                <span><Badge tone={scenario.effort === 'Adapté' ? 'teal' : scenario.effort === 'Léger' ? 'blue' : 'gold'}>{scenario.effort}</Badge></span>
              </div>
            ))}
          </div>
        </Card>
      </div>
    </div>
  )
}
