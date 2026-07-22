import { useState } from 'react'
import { Link } from 'react-router-dom'
import Icon from '../components/Icon'
import { DemoBanner, formatTnd } from '../components/Ui'

const mockAccounts = [
  { name: 'Compte courant', number: 'TN59 •••• •••• 2841', balance: 4850.25 },
  { name: 'Compte épargne', number: 'TN59 •••• •••• 9037', balance: 1990 },
]

export default function MockBankPage() {
  const [status, setStatus] = useState('pending')
  const [connected, setConnected] = useState(false)

  function handleLogin(event) {
    event.preventDefault()
    setConnected(true)
  }

  return (
    <main className="mock-bank-page">
      <header className="mock-bank-header">
        <Link className="mock-bank-brand" to="/banque-demo"><span>BA</span><strong>Banque Atlas <small>Espace de démonstration</small></strong></Link>
        <DemoBanner compact />
        <Link className="mock-bank-back" to="/connexion">Retour à Mon Équilibre</Link>
      </header>

      <div className="mock-bank-content">
        <section className="mock-bank-intro">
          <p className="eyebrow eyebrow--light">Fournisseur bancaire fictif</p>
          <h1>Autoriser l'accès à vos informations</h1>
          <p>Cette maquette illustre le consentement en lecture seule. Elle n'est reliée à aucune banque ni API.</p>
        </section>

        <div className="mock-bank-grid">
          <section className="mock-card mock-login-card" aria-labelledby="mock-login-title">
            <div className="mock-card__heading"><span className="mock-step">1</span><div><p>Connexion sécurisée</p><h2 id="mock-login-title">Client bancaire fictif</h2></div></div>
            {!connected ? (
              <form className="form-stack" onSubmit={handleLogin}>
                <label className="field"><span>Identifiant client</span><input defaultValue="DEMO-2481" required /></label>
                <label className="field"><span>Code secret</span><input defaultValue="demonstration" required type="password" /></label>
                <button className="mock-button mock-button--primary" type="submit"><Icon name="lock" size={18} /> Continuer</button>
                <p className="mock-helper">Ces identifiants sont fictifs et restent uniquement dans votre navigateur.</p>
              </form>
            ) : (
              <div className="mock-connected"><span><Icon name="check" /></span><h3>Bienvenue, Hédi Ben Amor</h3><p>Votre identité fictive a été confirmée pour cette démonstration.</p><button type="button" onClick={() => setConnected(false)}>Changer de client</button></div>
            )}
          </section>

          <section className="mock-card mock-consent-card" aria-labelledby="mock-consent-title">
            <div className="mock-card__heading"><span className="mock-step">2</span><div><p>Consentement demandé par</p><h2 id="mock-consent-title">Mon Équilibre</h2></div></div>

            <div className={`mock-status mock-status--${status}`} role="status">
              {status === 'pending' && 'Votre décision est nécessaire'}
              {status === 'authorized' && 'Accès en lecture seule autorisé'}
              {status === 'refused' && 'Demande refusée'}
              {status === 'revoked' && 'Autorisation révoquée'}
            </div>

            <h3>Informations qui seront consultées</h3>
            <ul className="mock-permissions"><li><Icon name="check" size={18} /> Vos comptes et leurs libellés</li><li><Icon name="check" size={18} /> Vos soldes disponibles</li><li><Icon name="check" size={18} /> L'historique de vos transactions</li></ul>

            <div className="mock-accounts">
              {mockAccounts.map((account) => <div key={account.number}><span><strong>{account.name}</strong><small>{account.number}</small></span><strong>{formatTnd(account.balance)}</strong></div>)}
            </div>

            <div className="mock-readonly"><Icon name="shield" /><p><strong>Aucun paiement possible</strong><span>Mon Équilibre ne pourra ni déplacer votre argent ni modifier vos comptes.</span></p></div>

            <div className="mock-actions">
              {status !== 'authorized' && <button className="mock-button mock-button--primary" disabled={!connected} type="button" onClick={() => setStatus('authorized')}>Autoriser l'accès en lecture seule</button>}
              {status === 'pending' && <button className="mock-button mock-button--secondary" type="button" onClick={() => setStatus('refused')}>Refuser</button>}
              {status === 'authorized' && <button className="mock-button mock-button--danger" type="button" onClick={() => setStatus('revoked')}>Révoquer l'autorisation</button>}
              {(status === 'refused' || status === 'revoked') && <button className="mock-button mock-button--secondary" type="button" onClick={() => setStatus('pending')}>Recommencer</button>}
            </div>
          </section>
        </div>
      </div>
    </main>
  )
}
