import { useEffect, useRef, useState } from 'react'
import { ApiError } from '../services/apiClient'
import { createImportAccount } from '../services/csvImportApi'
import { createManualTransaction, updateManualTransaction } from '../services/manualTransactionApi'
import { InlineNotice } from './Ui'
import { positiveDecimal } from '../services/manualTransactionValidation'

function message(error) {
  return error instanceof ApiError && error.status >= 400 && error.status < 500
    ? error.message
    : "L'enregistrement n'a pas pu être confirmé. Réessayez sans modifier les champs : la même clé évite les doublons."
}

export default function ManualTransactionForm({ accounts, categories, transaction, onSaved, onCancel }) {
  const heading = useRef(null)
  useEffect(() => { heading.current?.focus() }, [])
  const personal = transaction ? accounts : accounts.filter(account => account.accountOrigin === 'MANUAL' && account.localStatus === 'ACTIVE')
  const external = transaction && transaction.sourceType !== 'MANUAL'
  const title = transaction ? (external ? 'Modifier une opération' : 'Modifier une opération manuelle') : 'Ajouter une opération manuelle'
  const [accountId, setAccountId] = useState(transaction?.accountId || personal[0]?.id || '')
  const [createdAccount, setCreatedAccount] = useState(null)
  const [name, setName] = useState('Mes opérations personnelles')
  const [currency, setCurrency] = useState('TND')
  const [description, setDescription] = useState(transaction?.description || '')
  const [bookingDate, setBookingDate] = useState(transaction?.bookingDate || '')
  const [amount, setAmount] = useState(transaction?.amountExact || String(transaction?.amount ?? ''))
  const [direction, setDirection] = useState(transaction?.direction || 'DEBIT')
  const [categoryId, setCategoryId] = useState(transaction?.categoryId || '')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const key = useRef(null)
  const inFlight = useRef(false)
  const allAccounts = createdAccount && !personal.some(account => account.id === createdAccount.id)
    ? [...personal, createdAccount] : personal
  const selectedAccount = allAccounts.find(account => account.id === accountId)
  const compatible = categories.filter(category => category.nature === 'TRANSFER' || category.nature === 'NEUTRAL'
    || (direction === 'DEBIT' && category.nature === 'EXPENSE')
    || (direction === 'CREDIT' && category.nature === 'INCOME'))

  async function createAccount() {
    if (inFlight.current) return
    inFlight.current = true
    setBusy(true)
    setError('')
    try {
      const account = await createImportAccount({ name: name.trim(), currency, currentBalance: '0.0000' })
      setCreatedAccount({ ...account, displayName: account.name, accountOrigin: 'MANUAL', localStatus: 'ACTIVE' })
      setAccountId(account.id)
    } catch (failure) { setError(message(failure)) }
    finally { setBusy(false); inFlight.current = false }
  }

  async function submit(event) {
    event.preventDefault()
    if (inFlight.current) return
    const exactAmount = positiveDecimal(amount)
    if (!exactAmount) { setError('Saisissez un montant positif avec quatre décimales au maximum.'); return }
    if (!selectedAccount) { setError('Choisissez ou créez un compte personnel.'); return }
    inFlight.current = true
    setBusy(true)
    setError('')
    try {
      const fields = { bookingDate, description: description.trim(), amount: exactAmount, direction, categoryId: categoryId || null }
      let response
      if (transaction) response = await updateManualTransaction(transaction.id, { ...fields, version: transaction.version })
      else {
        key.current ||= crypto.randomUUID()
        response = await createManualTransaction({ ...fields, accountId, clientIdempotencyKey: key.current })
      }
      await onSaved(response)
    } catch (failure) { setError(message(failure)) }
    finally { setBusy(false); inFlight.current = false }
  }

  return (
    <section className="card manual-transaction-form" aria-label={title}>
      <h2 ref={heading} tabIndex={-1}>{title}</h2>
      <p className="muted">{external ? "Correction locale uniquement : la source bancaire ou le fichier d'origine est conservé. Une synchronisation ne remplace pas vos corrections. Les soldes bancaires restent inchangés." : 'Vos saisies personnelles restent séparées des données bancaires. Elles actualisent les flux et dépenses, pas les soldes de référence déclarés ou bancaires.'}</p>
      {error && <InlineNotice live tone="warning">{error}</InlineNotice>}
      {!transaction && <details open={allAccounts.length === 0}>
        <summary>Créer un compte personnel</summary>
        <div className="manual-transaction-grid">
          <label>Nom du compte<input disabled={busy} maxLength={160} value={name} onChange={event => setName(event.target.value)} /></label>
          <label>Devise du nouveau compte<select disabled={busy} value={currency} onChange={event => setCurrency(event.target.value)}><option>TND</option><option>EUR</option><option>USD</option></select></label>
        </div>
        <p className="muted">Le compte commence avec un solde de référence déclaré de zéro ; aucune transaction bancaire n'est créée.</p>
        <button type="button" className="button button--secondary" disabled={busy || !name.trim()} onClick={createAccount}>Créer ce compte</button>
      </details>}
      <form onSubmit={submit}>
        <fieldset disabled={busy}>
          <div className="manual-transaction-grid">
            <label>{external ? 'Compte (inchangé)' : 'Compte personnel'}<select required disabled={Boolean(transaction)} value={accountId} onChange={event => setAccountId(event.target.value)}><option value="">Choisir un compte</option>{allAccounts.map(account => <option value={account.id} key={account.id}>{account.displayName} · {account.currency}</option>)}</select></label>
            <label>Date de l'opération<input required type="date" min="1900-01-01" value={bookingDate} onChange={event => setBookingDate(event.target.value)} /></label>
            <label>Libellé<input required maxLength={1000} value={description} onChange={event => setDescription(event.target.value)} /></label>
            <label>Montant positif {selectedAccount && `(${selectedAccount.currency})`}<input required inputMode="decimal" value={amount} onChange={event => setAmount(event.target.value)} /></label>
            <label>Sens<select value={direction} onChange={event => { setDirection(event.target.value); setCategoryId('') }}><option value="DEBIT">Dépense / débit</option><option value="CREDIT">Revenu / crédit</option></select></label>
            <label>Catégorie de l'opération<select value={categoryId} onChange={event => setCategoryId(event.target.value)}><option value="">Non catégorisée</option>{compatible.map(category => <option key={category.id} value={category.id}>{category.name}</option>)}</select></label>
          </div>
          <div className="button-row"><button type="submit" className="button button--primary">{busy ? 'Enregistrement…' : 'Enregistrer cette opération'}</button><button type="button" className="button button--secondary" onClick={onCancel}>Annuler</button></div>
        </fieldset>
      </form>
    </section>
  )
}
