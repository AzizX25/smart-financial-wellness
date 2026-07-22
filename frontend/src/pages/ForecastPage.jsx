import { useState } from 'react'
import ForecastChart from '../components/ForecastChart'
import Icon from '../components/Icon'
import { Badge, Card, formatTnd, InlineNotice, PageHeader } from '../components/Ui'
import { forecast } from '../data/demoData'

export default function ForecastPage() {
  const [incomeChange, setIncomeChange] = useState(0)
  const [expenseChange, setExpenseChange] = useState(0)
  const simulatedBalance = forecast.savedEndBalance + incomeChange - expenseChange
  const difference = simulatedBalance - forecast.savedEndBalance

  return (
    <div className="page">
      <PageHeader
        eyebrow={`Prévision enregistrée le ${forecast.referenceDate}`}
        title="Ma prévision de fin de mois"
        description="Anticipez votre solde avec une estimation claire et testez vos propres hypothèses."
        action={<Badge tone="teal">Confiance élevée · {forecast.confidence} %</Badge>}
      />

      <div className="forecast-layout">
        <Card className="forecast-main-card">
          <div className="card-heading">
            <div><p className="eyebrow">Solde estimé au 31 juillet</p><h2 className="hero-amount">{formatTnd(forecast.savedEndBalance)}</h2></div>
            <span className="trend-pill"><Icon name="check" size={17} /> Solde positif</span>
          </div>
          <ForecastChart labels={forecast.labels} values={forecast.values} />
          <div className="forecast-explanation">
            <span className="soft-icon"><Icon name="info" /></span>
            <p><strong>Comment lire cette courbe ?</strong> Elle combine votre solde actuel, vos dépenses habituelles et les opérations prévues jusqu'à la fin du mois.</p>
          </div>
        </Card>

        <Card className="confidence-card">
          <p className="eyebrow">Fiabilité de l'estimation</p>
          <h2>Niveau de confiance</h2>
          <div className="confidence-gauge"><span style={{ width: `${forecast.confidence}%` }} /></div>
          <div className="confidence-label"><strong>Élevée</strong><span>{forecast.confidence} %</span></div>
          <ul className="simple-list">
            <li><Icon name="check" size={17} /> 7 mois d'historique analysés</li>
            <li><Icon name="check" size={17} /> Revenus réguliers détectés</li>
            <li><Icon name="check" size={17} /> Deux comptes inclus</li>
          </ul>
          <p className="disclaimer"><Icon name="info" size={17} /> Une prévision reste une estimation et peut évoluer.</p>
        </Card>
      </div>

      <Card className="forecast-simulator">
        <div className="card-heading">
          <div><p className="eyebrow">Simulation temporaire</p><h2>Que se passerait-il si… ?</h2><p className="muted">Explorez un changement sans remplacer la prévision enregistrée.</p></div>
          <Badge tone="gold">Non enregistrée</Badge>
        </div>
        <div className="forecast-simulator__body">
          <div className="simulation-inputs">
            <label className="field"><span>Revenu supplémentaire</span><span className="money-input"><input type="number" min="0" step="50" value={incomeChange} onChange={(event) => setIncomeChange(Number(event.target.value))} /><i>TND</i></span></label>
            <label className="field"><span>Dépense imprévue</span><span className="money-input"><input type="number" min="0" step="50" value={expenseChange} onChange={(event) => setExpenseChange(Number(event.target.value))} /><i>TND</i></span></label>
          </div>
          <div className="simulation-comparison">
            <div><span>Prévision enregistrée</span><strong>{formatTnd(forecast.savedEndBalance)}</strong><small>Référence conservée</small></div>
            <Icon name="arrow" size={26} />
            <div className="simulation-comparison__result"><span>Résultat simulé</span><strong>{formatTnd(simulatedBalance)}</strong><small className={difference >= 0 ? 'amount-credit' : 'amount-debit'}>{difference >= 0 ? '+' : '−'} {formatTnd(Math.abs(difference))} d'écart</small></div>
          </div>
        </div>
        <InlineNotice>Cette simulation vit uniquement dans votre navigateur et disparaît lorsque vous quittez la page.</InlineNotice>
      </Card>
    </div>
  )
}
