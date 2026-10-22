import { useState } from 'react'
import { useFinancialData } from '../financial/FinancialDataContext'
import { connectMockBank, disconnectMockBank } from '../services/bankApi'
import { ApiError } from '../services/apiClient'
import Icon from './Icon'
import { Badge, Card, formatCurrency, InlineNotice } from './Ui'

const accountTypes = {
  CASH: 'Espèces',
  CHECKING: 'Compte courant',
  OTHER: 'Autre compte',
  SAVINGS: 'Compte d’épargne',
}

const connectionStatuses = {
  ACTIVE: 'Connectée',
  DISCONNECTED: 'Déconnectée',
  ERROR: 'À reconnecter',
  PENDING: 'Connexion en cours',
}

function formatDate(value, dateOnly = false) {
  if (!value) return 'Non disponible'
  const date = new Date(dateOnly ? `${value}T00:00:00` : value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat('fr-TN', dateOnly
    ? { dateStyle: 'medium' }
    : { dateStyle: 'long', timeStyle: 'short' }).format(date)
}

function controlledBankError(error) {
  if (!(error instanceof ApiError)) return 'Une erreur inattendue est survenue.'
  if (error.status === 0 || error.status === 503) return 'La banque simulée est momentanément indisponible.'
  if (error.status === 422) return 'Identifiants de banque simulée incorrects ou compte bancaire inactif.'
  if (error.status === 409 && error.message.includes('another user')) {
    return 'Ce client bancaire est déjà lié à un autre utilisateur de l’application.'
  }
  if (error.status === 409 && error.message.includes('Disconnect the existing')) {
    return 'Une autre connexion bancaire est déjà active. Déconnectez-la avant de continuer.'
  }
  return 'La demande bancaire n’a pas pu être traitée.'
}

export default function BankConnectionSection() {
  const {
    accounts,
    connected,
    connection,
    error: dataError,
    historical,
    loading,
    recentTransactions,
    reload,
    synchronizationError,
    synchronizationStatus,
    synchronize,
    synchronizing,
  } = useFinancialData()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [validationError, setValidationError] = useState('')
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [submitting, setSubmitting] = useState(false)

  async function handleConnect(event) {
    event.preventDefault()
    if (submitting || synchronizing) return
    if (!username.trim() || !password) {
      setValidationError('Saisissez l’identifiant et le mot de passe de la banque simulée.')
      return
    }

    setSubmitting(true)
    setValidationError('')
    setError('')
    setNotice('')
    const submittedPassword = password
    setPassword('')
    try {
      await connectMockBank({ username: username.trim(), password: submittedPassword })
      const synchronized = await synchronize()
      setNotice(synchronized
        ? 'La banque simulée est connectée et les données locales sont synchronisées.'
        : 'La banque simulée est connectée, mais la synchronisation doit être relancée.')
    } catch (requestError) {
      setError(controlledBankError(requestError))
    } finally {
      setSubmitting(false)
    }
  }

  async function handleRefresh() {
    setNotice('')
    const synchronized = await synchronize()
    if (synchronized) setNotice('Les données financières locales ont été synchronisées.')
  }

  async function handleDisconnect() {
    if (!window.confirm('Déconnecter la banque simulée ? Les données déjà synchronisées resteront consultables.')) return
    setSubmitting(true)
    setError('')
    setNotice('')
    try {
      await disconnectMockBank()
      await reload({ quiet: true })
      setUsername('')
      setPassword('')
      setNotice('La banque est déconnectée. Votre historique synchronisé reste disponible en lecture seule.')
    } catch (requestError) {
      setError(controlledBankError(requestError))
    } finally {
      setSubmitting(false)
    }
  }

  if (loading && connection === undefined) {
    return <Card className="bank-journey bank-state" aria-live="polite"><span className="auth-spinner" /> Chargement de la connexion bancaire…</Card>
  }

  const reconnecting = Boolean(connection?.reconnectRequired && connection?.status !== 'DISCONNECTED')

  return (
    <section className="bank-journey" aria-labelledby="bank-connection-title">
      <div className="section-heading">
        <div><p className="eyebrow">Connexion bancaire</p><h2 id="bank-connection-title">Banque simulée</h2></div>
        <Badge tone={connected ? 'teal' : reconnecting ? 'rose' : 'neutral'}>
          {connectionStatuses[connection?.status] || 'Non connectée'}
        </Badge>
      </div>

      <InlineNotice tone="info" icon="shield">
        Les identifiants bancaires sont transmis une seule fois au backend principal. Le navigateur ne contacte jamais directement la banque simulée.
      </InlineNotice>
      {historical && <InlineNotice tone="warning" icon="info">Données historiques : reconnectez la banque pour les actualiser.</InlineNotice>}
      {notice && <InlineNotice live tone="success" icon="check">{notice}</InlineNotice>}
      {(error || dataError) && <InlineNotice live tone="warning" icon="alert">{error || dataError}</InlineNotice>}
      {synchronizationError && <InlineNotice live tone="warning" icon="alert">{synchronizationError} Les données précédentes restent affichées.</InlineNotice>}

      {!connected && (
        <Card className="bank-connect-card">
          <div className="card-heading">
            <div>
              <p className="eyebrow">{reconnecting ? 'Autorisation expirée' : historical ? 'Actualisation suspendue' : 'Aucune banque connectée'}</p>
              <h3>{reconnecting || historical ? 'Reconnecter la banque simulée' : 'Connecter ma banque'}</h3>
            </div>
            <span className="soft-icon"><Icon name="bank" /></span>
          </div>
          <p className="muted">La reconnexion permet de synchroniser de nouvelles opérations sans supprimer l’historique local.</p>
          <form className="bank-connect-form" noValidate onSubmit={handleConnect}>
            <label className="field"><span>Identifiant mock-bank</span><input autoComplete="off" maxLength={80} onChange={(event) => { setUsername(event.target.value); setValidationError('') }} required value={username} /></label>
            <label className="field"><span>Mot de passe mock-bank</span><input autoComplete="off" maxLength={200} onChange={(event) => { setPassword(event.target.value); setValidationError('') }} required type="password" value={password} /></label>
            {validationError && <p className="field-error" role="alert">{validationError}</p>}
            <button className="button button--primary" disabled={submitting || synchronizing} type="submit">
              {submitting || synchronizing ? 'Connexion et synchronisation en cours…' : reconnecting || historical ? 'Reconnecter la banque' : 'Connecter ma banque'}
            </button>
          </form>
        </Card>
      )}

      {connected && (
        <Card className="connection-summary">
          <div className="connection-card__brand"><span>BS</span><div><p className="eyebrow">Fournisseur de développement</p><h3>Banque simulée</h3></div></div>
          <dl className="detail-list">
            <div><dt>État</dt><dd>{connectionStatuses[connection.status]}</dd></div>
            <div><dt>Dernière synchronisation</dt><dd>{formatDate(synchronizationStatus?.lastSuccessfulAt)}</dd></div>
            <div><dt>Statut de synchronisation</dt><dd>{synchronizationStatus?.status || 'Jamais synchronisée'}</dd></div>
          </dl>
          <div className="button-row">
            <button className="button button--secondary" disabled={synchronizing} onClick={handleRefresh} type="button"><Icon name="transactions" size={18} /> {synchronizing ? 'Synchronisation en cours…' : 'Actualiser'}</button>
            <button className="button button--danger-quiet" disabled={submitting || synchronizing} onClick={handleDisconnect} type="button">Déconnecter la banque</button>
          </div>
        </Card>
      )}

      {(accounts.length > 0 || connected) && (
        <div className="bank-live-data">
          <div className="section-heading"><div><p className="eyebrow">Données locales normalisées</p><h3>Comptes et opérations récentes</h3></div><Badge tone="blue">{accounts.length} compte{accounts.length === 1 ? '' : 's'}</Badge></div>
          {accounts.length === 0 ? (
            <Card className="empty-state"><Icon name="bank" /><h3>Aucun compte synchronisé</h3><p>Lancez une synchronisation pour charger les comptes disponibles.</p></Card>
          ) : accounts.map((account) => {
            const transactions = recentTransactions.filter((transaction) => transaction.accountId === account.id)
            return (
              <Card className="live-account" key={account.id}>
                <div className="live-account__header">
                  <div><p className="eyebrow">{accountTypes[account.accountType] || account.accountType}</p><h3>{account.displayName || 'Compte sans libellé'}</h3><span>{account.maskedAccountReference || 'Référence masquée indisponible'}</span></div>
                  <Badge tone={account.localStatus === 'ACTIVE' ? 'mint' : 'neutral'}>{account.localStatus === 'ACTIVE' ? 'Actif' : 'Historique'}</Badge>
                </div>
                <div className="balance-grid">
                  <div><span>Solde actuel</span><strong>{formatCurrency(account.currentBalance, account.currency)}</strong></div>
                  <div><span>Solde disponible</span><strong>{formatCurrency(account.availableBalance, account.currency)}</strong></div>
                </div>
                <div className="live-transactions"><h4>Opérations récentes</h4>
                  {transactions.length === 0 ? <p className="muted">Aucune transaction récente pour ce compte.</p> : transactions.map((transaction) => (
                    <div className="live-transaction" key={transaction.id}>
                      <span className={`transaction-row__icon transaction-row__icon--${transaction.direction === 'CREDIT' ? 'income' : 'expense'}`} aria-hidden="true">{transaction.direction === 'CREDIT' ? '↓' : '↑'}</span>
                      <div><strong>{transaction.description || 'Opération sans libellé'}</strong><span>{formatDate(transaction.bookingDate, true)} · {transaction.direction === 'CREDIT' ? 'Crédit' : 'Débit'}</span></div>
                      <strong className={transaction.direction === 'CREDIT' ? 'amount-credit' : 'amount-debit'}>{transaction.direction === 'CREDIT' ? '+' : '−'} {formatCurrency(transaction.amount, transaction.currency)}</strong>
                    </div>
                  ))}
                </div>
              </Card>
            )
          })}
        </div>
      )}
    </section>
  )
}
