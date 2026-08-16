import { useCallback, useEffect, useState } from 'react'
import { Link } from 'wouter'
import { adminApi } from '../api/adminApi'
import { Empty, ErrorMessage, Loading, Pagination, StatusBadge, SuccessMessage } from '../components/Ui'
import { formatMoney } from '../utils/format'

const emptyForm = { customerId: '', displayName: '', accountType: 'CHECKING', currency: 'TND' }

export default function AccountsPage() {
  const searchParams = new URLSearchParams(window.location.search)
  const [result, setResult] = useState(null)
  const [page, setPage] = useState(0)
  const [status, setStatus] = useState('')
  const [accountType, setAccountType] = useState('')
  const [form, setForm] = useState({ ...emptyForm, customerId: searchParams.get('customerId') || '' })
  const [error, setError] = useState('')
  const [success, setSuccess] = useState('')
  const customerId = searchParams.get('customerId') || ''

  const load = useCallback(() => {
    setError('')
    adminApi.accounts({ customerId, status, accountType, page, size: 10 })
      .then(setResult).catch((apiError) => setError(apiError.message))
  }, [customerId, status, accountType, page])
  useEffect(load, [load])

  async function create(event) {
    event.preventDefault()
    try {
      const { customerId: owner, ...account } = form
      await adminApi.createAccount(owner, account)
      setSuccess('Le compte a été créé avec un solde nul.')
      setForm({ ...emptyForm, customerId: owner })
      load()
    } catch (apiError) { setError(apiError.message) }
  }

  return <section>
    <div className="page-heading"><div><p className="eyebrow">Données bancaires simulées</p><h2>Comptes</h2></div></div>
    <ErrorMessage message={error} /><SuccessMessage message={success} />
    <div className="two-columns">
      <article className="panel"><div className="filters">
        <select aria-label="Filtrer par état" value={status} onChange={(event) => { setStatus(event.target.value); setPage(0) }}><option value="">Tous les états</option><option value="ACTIVE">Actif</option><option value="INACTIVE">Gelé</option><option value="CLOSED">Clôturé</option></select>
        <select aria-label="Filtrer par type" value={accountType} onChange={(event) => { setAccountType(event.target.value); setPage(0) }}><option value="">Tous les types</option><option>CHECKING</option><option>SAVINGS</option><option>CASH</option><option>OTHER</option></select>
      </div>
        {!result ? <Loading /> : result.items.length === 0 ? <Empty>Aucun compte trouvé.</Empty> : <div className="table-wrap"><table><thead><tr><th>Compte</th><th>Client</th><th>Solde</th><th>État</th></tr></thead><tbody>
          {result.items.map((account) => <tr key={account.accountId}><td><Link href={`/comptes/${account.accountId}`}>{account.displayName}</Link><small>{account.accountId}</small></td><td><Link href={`/clients/${account.customerId}`}>{account.customerId}</Link></td><td>{formatMoney(account.currentBalance, account.currency)}</td><td><StatusBadge status={account.status} /></td></tr>)}
        </tbody></table></div>}
        {result && <Pagination page={result.page} totalPages={result.totalPages} onPage={setPage} />}
      </article>
      <article className="panel compact"><h3>Créer un compte</h3><form onSubmit={create}>
        <label>Identifiant externe du client<input required value={form.customerId} onChange={(event) => setForm({ ...form, customerId: event.target.value })} /></label>
        <label>Nom du compte<input required value={form.displayName} onChange={(event) => setForm({ ...form, displayName: event.target.value })} /></label>
        <label>Type<select value={form.accountType} onChange={(event) => setForm({ ...form, accountType: event.target.value })}><option>CHECKING</option><option>SAVINGS</option><option>CASH</option><option>OTHER</option></select></label>
        <label>Devise<input required maxLength="3" value={form.currency} onChange={(event) => setForm({ ...form, currency: event.target.value })} /></label>
        <button className="button primary">Créer avec un solde nul</button>
      </form></article>
    </div>
  </section>
}
