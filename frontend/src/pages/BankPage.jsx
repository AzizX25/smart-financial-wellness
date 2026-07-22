import { useState } from 'react'
import { Link } from 'react-router-dom'
import Icon from '../components/Icon'
import { Badge, Card, formatTnd, InlineNotice, PageHeader } from '../components/Ui'
import { accounts, bankConnection } from '../data/demoData'

export default function BankPage() {
  const [connectionStatus, setConnectionStatus] = useState('connected')
  const [feedback, setFeedback] = useState('')

  function simulateSync() {
    setFeedback('Synchronisation de démonstration terminée : aucune donnée réelle n’a été échangée.')
  }

  function simulateRevocation() {
    setConnectionStatus('revoked')
    setFeedback('Autorisation révoquée dans la maquette. Rechargez la page pour réinitialiser la démonstration.')
  }

  return (
    <div className="page">
      <PageHeader
        eyebrow="Connexion sécurisée"
        title="Ma banque"
        description="Consultez l'état de vos comptes synchronisés et gardez le contrôle de l'autorisation."
        action={<Link className="button button--secondary" to="/banque-demo">Voir le parcours de consentement</Link>}
      />

      <InlineNotice tone="success" icon="shield">
        <strong>Lecture seule.</strong> Mon Équilibre peut consulter vos comptes, soldes et transactions,
        mais ne peut effectuer ni paiement ni virement.
      </InlineNotice>

      {feedback && <InlineNotice live tone={connectionStatus === 'revoked' ? 'warning' : 'success'}>{feedback}</InlineNotice>}

      <div className="bank-grid">
        <Card className="connection-card">
          <div className="connection-card__brand"><span>BA</span><div><p className="eyebrow">Banque partenaire fictive</p><h2>{bankConnection.provider}</h2></div></div>
          <dl className="detail-list">
            <div><dt>État de la connexion</dt><dd><Badge tone={connectionStatus === 'connected' ? 'teal' : 'rose'}>{connectionStatus === 'connected' ? bankConnection.status : 'Révoquée'}</Badge></dd></div>
            <div><dt>Dernière synchronisation</dt><dd>{bankConnection.lastSync}</dd></div>
            <div><dt>Fin de l'autorisation</dt><dd>{bankConnection.authorizationExpires}</dd></div>
          </dl>
          <div className="button-row">
            <button className="button button--primary" disabled={connectionStatus === 'revoked'} type="button" onClick={simulateSync}>
              <Icon name="transactions" size={18} /> Synchroniser maintenant
            </button>
            <button className="button button--danger-quiet" disabled={connectionStatus === 'revoked'} type="button" onClick={simulateRevocation}>
              Révoquer l'autorisation
            </button>
          </div>
        </Card>

        <Card>
          <p className="eyebrow">Ce que vous autorisez</p>
          <h2>Un accès limité et transparent</h2>
          <ul className="permission-list">
            {bankConnection.scopes.map((scope) => <li key={scope}><Icon name="check" size={18} /> {scope}</li>)}
          </ul>
          <p className="muted">Vous pouvez révoquer cet accès à tout moment. Les références secrètes restent hors de la base applicative.</p>
        </Card>
      </div>

      <section className="section-block" aria-labelledby="synced-accounts-title">
        <div className="section-heading"><div><p className="eyebrow">Comptes associés</p><h2 id="synced-accounts-title">Vos comptes synchronisés</h2></div><Badge tone="blue">{accounts.length} comptes</Badge></div>
        <div className="account-grid">
          {accounts.map((account) => (
            <Card className="account-card" key={account.id}>
              <span className="soft-icon"><Icon name={account.type === 'Courant' ? 'wallet' : 'target'} /></span>
              <div><p className="muted">{account.type} · {account.maskedNumber}</p><h3>{account.name}</h3><strong>{formatTnd(account.balance)}</strong></div>
              <Badge tone={connectionStatus === 'connected' ? 'mint' : 'neutral'}>{connectionStatus === 'connected' ? 'À jour' : 'Hors ligne'}</Badge>
            </Card>
          ))}
        </div>
      </section>
    </div>
  )
}
