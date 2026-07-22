import { useState } from 'react'
import Icon from '../components/Icon'
import { Badge, Card, InlineNotice, PageHeader } from '../components/Ui'
import { bankConnection, consentSettings, profile } from '../data/demoData'

export default function SettingsPage() {
  const [feedback, setFeedback] = useState('')

  return (
    <div className="page">
      <PageHeader
        eyebrow="Votre espace personnel"
        title="Profil et réglages"
        description="Adaptez l'expérience à vos besoins et gardez le contrôle de vos données."
      />

      {feedback && <InlineNotice live tone="success">{feedback}</InlineNotice>}

      <div className="settings-grid">
        <Card className="profile-card">
          <div className="profile-card__identity"><span className="avatar avatar--large">AT</span><div><h2>{profile.fullName}</h2><p>{profile.email}</p><Badge tone="teal">Compte actif</Badge></div></div>
          <dl className="detail-list"><div><dt>Ville</dt><dd>{profile.city}</dd></div><div><dt>Langue</dt><dd>{profile.language}</dd></div></dl>
          <button className="button button--secondary" type="button">Modifier mon profil</button>
        </Card>

        <Card>
          <div className="card-heading"><div><p className="eyebrow">Préférences financières</p><h2>Mes repères</h2></div><span className="soft-icon"><Icon name="wallet" /></span></div>
          <label className="field"><span>Devise principale</span><select defaultValue="TND"><option value="TND">Dinar tunisien (TND)</option><option value="EUR">Euro (EUR)</option></select></label>
          <label className="field"><span>Début de la semaine</span><select defaultValue="monday"><option value="monday">Lundi</option><option value="sunday">Dimanche</option></select></label>
        </Card>

        <Card>
          <div className="card-heading"><div><p className="eyebrow">Interface</p><h2>Confort d'utilisation</h2></div><span className="soft-icon"><Icon name="user" /></span></div>
          <div className="toggle-list">
            <label><span><strong>Texte plus grand</strong><small>Améliore la lisibilité</small></span><input type="checkbox" /></label>
            <label><span><strong>Contraste renforcé</strong><small>Distingue mieux les éléments</small></span><input type="checkbox" /></label>
            <label><span><strong>Animations réduites</strong><small>Limite les mouvements à l'écran</small></span><input type="checkbox" /></label>
          </div>
        </Card>

        <Card>
          <div className="card-heading"><div><p className="eyebrow">Notifications</p><h2>Ce que je souhaite recevoir</h2></div><span className="soft-icon"><Icon name="bell" /></span></div>
          <div className="toggle-list">
            <label><span><strong>Alertes importantes</strong><small>Solde faible ou dépense inhabituelle</small></span><input type="checkbox" defaultChecked /></label>
            <label><span><strong>Progrès des objectifs</strong><small>Une fois par mois</small></span><input type="checkbox" defaultChecked /></label>
            <label><span><strong>Conseils hebdomadaires</strong><small>Résumé simple chaque dimanche</small></span><input type="checkbox" /></label>
          </div>
        </Card>
      </div>

      <Card className="settings-section">
        <div className="card-heading"><div><p className="eyebrow">Connexion bancaire</p><h2>{bankConnection.provider}</h2></div><Badge tone="teal">{bankConnection.status}</Badge></div>
        <div className="settings-bank"><span className="soft-icon"><Icon name="bank" /></span><p>Deux comptes synchronisés. Dernière mise à jour : {bankConnection.lastSync}.</p><button className="button button--secondary" type="button">Gérer la connexion</button></div>
      </Card>

      <Card className="settings-section">
        <div className="card-heading"><div><p className="eyebrow">Consentements</p><h2>Vos choix de confidentialité</h2></div><Badge tone="blue">Modifiables</Badge></div>
        <div className="consent-list">
          {consentSettings.map((consent) => <div key={consent.label}><span><strong>{consent.label}</strong><small>{consent.detail}</small></span><Badge tone="mint">{consent.status}</Badge></div>)}
        </div>
      </Card>

      <Card className="data-control-card">
        <div><span className="soft-icon"><Icon name="shield" /></span><div><p className="eyebrow">Vos données, vos droits</p><h2>Exporter ou supprimer mes données</h2><p className="muted">L'export et la suppression passeront par un workflow sécurisé avec confirmation.</p></div></div>
        <div className="button-row">
          <button className="button button--secondary" type="button" onClick={() => setFeedback("Demande d'export simulée : aucun fichier réel n'a été généré.")}>Demander un export</button>
          <button className="button button--danger-quiet" type="button" onClick={() => setFeedback("Demande de suppression simulée : aucune donnée n'a été modifiée.")}>Demander la suppression du compte</button>
        </div>
      </Card>
    </div>
  )
}
