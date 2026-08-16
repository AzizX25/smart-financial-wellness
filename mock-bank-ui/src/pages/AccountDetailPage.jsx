import { useCallback, useEffect, useState } from 'react'
import { Link } from 'wouter'
import { adminApi } from '../api/adminApi'
import { Empty, ErrorMessage, Loading, Pagination, StatusBadge, SuccessMessage } from '../components/Ui'
import { formatDate, formatMoney } from '../utils/format'

const emptyOperation = { amount: '', direction: 'CREDIT', description: '', bookingDate: '', reference: '', counterpartyName: '' }

export default function AccountDetailPage({ accountId }) {
  const [account, setAccount] = useState(null)
  const [transactions, setTransactions] = useState(null)
  const [page, setPage] = useState(0)
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [operation, setOperation] = useState(emptyOperation)
  const [error, setError] = useState('')
  const [success, setSuccess] = useState('')

  const load = useCallback(async () => {
    setError('')
    try {
      const [accountData, transactionData] = await Promise.all([
        adminApi.account(accountId), adminApi.transactions(accountId, { from, to, page, size: 10 }),
      ])
      setAccount(accountData); setTransactions(transactionData)
    } catch (apiError) { setError(apiError.message) }
  }, [accountId, from, to, page])
  useEffect(() => { load() }, [load])

  async function update(event) {
    event.preventDefault()
    try {
      setAccount(await adminApi.updateAccount(accountId, { displayName: account.displayName, accountType: account.accountType }))
      setSuccess('Le compte a été mis à jour.')
    } catch (apiError) { setError(apiError.message) }
  }

  async function action(name) {
    if (name === 'close' && !window.confirm('Clôturer définitivement ce compte ?')) return
    try { setAccount(await adminApi.accountAction(accountId, name)); setSuccess('État du compte mis à jour.') }
    catch (apiError) { setError(apiError.message) }
  }

  async function createOperation(event) {
    event.preventDefault()
    if (!window.confirm(`Confirmer ce ${operation.direction === 'CREDIT' ? 'crédit' : 'débit'} ?`)) return
    try {
      const payload = { ...operation, amount: Number(operation.amount) }
      if (!payload.bookingDate) delete payload.bookingDate
      if (!payload.reference) delete payload.reference
      if (!payload.counterpartyName) delete payload.counterpartyName
      await adminApi.createTransaction(accountId, payload)
      setOperation(emptyOperation)
      setSuccess('L’opération a été enregistrée et le solde actualisé.')
      setPage(0)
      await load()
    } catch (apiError) { setError(apiError.message); setOperation((current) => ({ ...current, amount: '' })) }
  }

  if (!account && !error) return <Loading />
  return <section>
    <div className="page-heading"><div><Link href="/comptes">← Comptes</Link><h2>{account?.displayName || 'Compte'}</h2></div>{account && <StatusBadge status={account.status} />}</div>
    <ErrorMessage message={error} /><SuccessMessage message={success} />
    {account && <>
      <div className="card-grid account-summary"><article className="stat-card"><span>Solde courant</span><strong>{formatMoney(account.currentBalance, account.currency)}</strong><small>{account.accountId}</small></article><article className="stat-card"><span>Solde disponible</span><strong>{formatMoney(account.availableBalance, account.currency)}</strong><small>{account.accountType}</small></article></div>
      <div className="two-columns">
        <article className="panel compact"><h3>Paramètres du compte</h3><form onSubmit={update}>
          <label>Nom<input value={account.displayName} onChange={(event) => setAccount({ ...account, displayName: event.target.value })} /></label>
          <label>Type<select value={account.accountType} onChange={(event) => setAccount({ ...account, accountType: event.target.value })}><option>CHECKING</option><option>SAVINGS</option><option>CASH</option><option>OTHER</option></select></label>
          <button className="button primary">Enregistrer</button>
          {account.status === 'ACTIVE' && <button type="button" className="button secondary" onClick={() => action('freeze')}>Geler</button>}
          {account.status === 'INACTIVE' && <button type="button" className="button secondary" onClick={() => action('activate')}>Activer</button>}
          {account.status !== 'CLOSED' && <button type="button" className="button danger" onClick={() => action('close')}>Clôturer</button>}
        </form></article>
        <article className="panel compact"><h3>Nouvelle opération</h3><form onSubmit={createOperation}>
          <label>Nature<select value={operation.direction} onChange={(event) => setOperation({ ...operation, direction: event.target.value })}><option value="CREDIT">Crédit</option><option value="DEBIT">Débit</option></select></label>
          <label>Montant<input aria-label="Montant" type="number" min="0.0001" step="0.0001" required value={operation.amount} onChange={(event) => setOperation({ ...operation, amount: event.target.value })} /></label>
          <label>Description<input required value={operation.description} onChange={(event) => setOperation({ ...operation, description: event.target.value })} /></label>
          <label>Date<input type="date" value={operation.bookingDate} onChange={(event) => setOperation({ ...operation, bookingDate: event.target.value })} /></label>
          <label>Référence facultative<input value={operation.reference} onChange={(event) => setOperation({ ...operation, reference: event.target.value })} /></label>
          <button className="button primary" disabled={account.status !== 'ACTIVE'}>Enregistrer l’opération</button>
        </form></article>
      </div>
    </>}
    <article className="panel"><div className="panel-title"><h3>Historique des opérations</h3><div className="filters"><label>Du<input type="date" value={from} onChange={(event) => { setFrom(event.target.value); setPage(0) }} /></label><label>Au<input type="date" value={to} onChange={(event) => { setTo(event.target.value); setPage(0) }} /></label></div></div>
      {!transactions ? <Loading /> : transactions.items.length === 0 ? <Empty>Aucune opération enregistrée.</Empty> : <div className="table-wrap"><table><thead><tr><th>Date</th><th>Référence</th><th>Description</th><th>Montant</th></tr></thead><tbody>
        {transactions.items.map((transaction) => <tr key={transaction.transactionId}><td>{formatDate(transaction.bookingDate)}</td><td>{transaction.transactionId}</td><td>{transaction.description}</td><td className={transaction.direction === 'CREDIT' ? 'credit' : 'debit'}>{transaction.direction === 'CREDIT' ? '+' : '−'} {formatMoney(transaction.amount, transaction.currency)}</td></tr>)}
      </tbody></table></div>}
      {transactions && <Pagination page={transactions.page} totalPages={transactions.totalPages} onPage={setPage} />}
    </article>
  </section>
}
