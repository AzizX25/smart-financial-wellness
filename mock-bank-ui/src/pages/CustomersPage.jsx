import { useCallback, useEffect, useState } from 'react'
import { Link } from 'wouter'
import { adminApi } from '../api/adminApi'
import { Empty, ErrorMessage, Loading, Pagination, StatusBadge, SuccessMessage } from '../components/Ui'

const emptyForm = { displayName: '', username: '', password: '', countryCode: 'TN' }

export default function CustomersPage() {
  const [result, setResult] = useState(null)
  const [search, setSearch] = useState('')
  const [page, setPage] = useState(0)
  const [form, setForm] = useState(emptyForm)
  const [error, setError] = useState('')
  const [success, setSuccess] = useState('')

  const load = useCallback(() => {
    setError('')
    adminApi.customers({ search, page, size: 10 }).then(setResult).catch((apiError) => setError(apiError.message))
  }, [page, search])

  useEffect(load, [load])

  async function create(event) {
    event.preventDefault()
    setError('')
    try {
      await adminApi.createCustomer(form)
      setSuccess('Le client a été créé.')
      setForm(emptyForm)
      setPage(0)
      load()
    } catch (apiError) {
      setError(apiError.message)
    } finally {
      setForm((current) => ({ ...current, password: '' }))
    }
  }

  return <section>
    <div className="page-heading"><div><p className="eyebrow">Données de test</p><h2>Clients</h2></div></div>
    <ErrorMessage message={error} /><SuccessMessage message={success} />
    <div className="two-columns">
      <article className="panel">
        <form className="search" onSubmit={(event) => { event.preventDefault(); setPage(0); load() }}>
          <input aria-label="Rechercher un client" placeholder="Nom ou identifiant…" value={search} onChange={(event) => setSearch(event.target.value)} />
          <button className="button secondary">Rechercher</button>
        </form>
        {!result ? <Loading /> : result.items.length === 0 ? <Empty>Aucun client trouvé.</Empty> : (
          <div className="table-wrap"><table><thead><tr><th>Client</th><th>Identifiant</th><th>Pays</th><th>État</th></tr></thead>
            <tbody>{result.items.map((customer) => <tr key={customer.customerId}>
              <td><Link href={`/clients/${customer.customerId}`}>{customer.displayName}</Link><small>{customer.customerId}</small></td>
              <td>{customer.username}</td><td>{customer.countryCode}</td><td><StatusBadge status={customer.status} /></td>
            </tr>)}</tbody></table></div>
        )}
        {result && <Pagination page={result.page} totalPages={result.totalPages} onPage={setPage} />}
      </article>
      <article className="panel compact">
        <h3>Créer un client</h3>
        <form onSubmit={create}>
          <label>Nom affiché<input required maxLength="160" value={form.displayName} onChange={(event) => setForm({ ...form, displayName: event.target.value })} /></label>
          <label>Nom d’utilisateur<input required maxLength="80" value={form.username} onChange={(event) => setForm({ ...form, username: event.target.value })} /></label>
          <label>Mot de passe<input type="password" required minLength="8" value={form.password} onChange={(event) => setForm({ ...form, password: event.target.value })} /></label>
          <label>Pays<input required pattern="[A-Za-z]{2}" maxLength="2" value={form.countryCode} onChange={(event) => setForm({ ...form, countryCode: event.target.value })} /></label>
          <button className="button primary">Créer le client</button>
        </form>
      </article>
    </div>
  </section>
}
