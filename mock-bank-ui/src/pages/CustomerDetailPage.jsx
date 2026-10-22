import { useCallback, useEffect, useState } from 'react'
import { Link } from 'wouter'
import { adminApi } from '../api/adminApi'
import { Empty, ErrorMessage, Loading, StatusBadge, SuccessMessage } from '../components/Ui'
import { formatMoney } from '../utils/format'

export default function CustomerDetailPage({ customerId }) {
  const [customer, setCustomer] = useState(null)
  const [accounts, setAccounts] = useState([])
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [success, setSuccess] = useState('')

  const load = useCallback(async () => {
    try {
      const [customerData, accountData] = await Promise.all([
        adminApi.customer(customerId), adminApi.accounts({ customerId, size: 100 }),
      ])
      setCustomer(customerData)
      setAccounts(accountData.items)
    } catch (apiError) { setError(apiError.message) }
  }, [customerId])

  useEffect(() => { load() }, [load])

  async function update(event) {
    event.preventDefault()
    try {
      const updated = await adminApi.updateCustomer(customerId, {
        displayName: customer.displayName, username: customer.username, countryCode: customer.countryCode,
      })
      setCustomer(updated); setSuccess('Les informations ont été enregistrées.')
    } catch (apiError) { setError(apiError.message) }
  }

  async function resetPassword(event) {
    event.preventDefault()
    try {
      await adminApi.resetCustomerPassword(customerId, password)
      setSuccess('Le mot de passe a été remplacé.')
    } catch (apiError) { setError(apiError.message) } finally { setPassword('') }
  }

  async function toggleStatus() {
    const action = customer.status === 'ACTIVE' ? 'deactivate' : 'activate'
    if (action === 'deactivate' && !window.confirm('Désactiver ce client ?')) return
    try { setCustomer(await adminApi.customerAction(customerId, action)); setSuccess('État du client mis à jour.') }
    catch (apiError) { setError(apiError.message) }
  }

  if (!customer && !error) return <Loading />
  return <section>
    <div className="page-heading"><div><Link href="/clients">← Clients</Link><h2>{customer?.displayName || 'Client'}</h2></div>{customer && <StatusBadge status={customer.status} />}</div>
    <ErrorMessage message={error} /><SuccessMessage message={success} />
    {customer && <div className="two-columns">
      <article className="panel compact"><h3>Profil de test</h3><form onSubmit={update}>
        <label>Nom affiché<input value={customer.displayName} onChange={(event) => setCustomer({ ...customer, displayName: event.target.value })} /></label>
        <label>Nom d’utilisateur<input value={customer.username} onChange={(event) => setCustomer({ ...customer, username: event.target.value })} /></label>
        <label>Pays<input maxLength="2" value={customer.countryCode} onChange={(event) => setCustomer({ ...customer, countryCode: event.target.value })} /></label>
        <button className="button primary">Enregistrer</button><button type="button" className="button secondary" onClick={toggleStatus}>{customer.status === 'ACTIVE' ? 'Désactiver' : 'Activer'}</button>
      </form></article>
      <article className="panel compact"><h3>Réinitialiser le mot de passe</h3><form onSubmit={resetPassword}>
        <label>Nouveau mot de passe<input type="password" minLength="8" required value={password} onChange={(event) => setPassword(event.target.value)} /></label>
        <button className="button secondary">Remplacer le mot de passe</button>
      </form></article>
    </div>}
    <article className="panel"><div className="panel-title"><h3>Comptes du client</h3><Link className="button secondary" href={`/comptes?customerId=${customerId}`}>Gérer les comptes</Link></div>
      {accounts.length === 0 ? <Empty>Aucun compte pour ce client.</Empty> : <div className="table-wrap"><table><thead><tr><th>Compte</th><th>Type</th><th>Solde</th><th>État</th></tr></thead><tbody>
        {accounts.map((account) => <tr key={account.accountId}><td><Link href={`/comptes/${account.accountId}`}>{account.displayName}</Link><small>{account.accountId}</small></td><td>{account.accountType}</td><td>{formatMoney(account.currentBalance, account.currency)}</td><td><StatusBadge status={account.status} /></td></tr>)}
      </tbody></table></div>}
    </article>
  </section>
}
