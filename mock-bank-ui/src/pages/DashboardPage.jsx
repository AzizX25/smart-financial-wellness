import { useEffect, useState } from 'react'
import { adminApi } from '../api/adminApi'
import { Empty, ErrorMessage, Loading } from '../components/Ui'
import { formatDate, formatMoney } from '../utils/format'

export default function DashboardPage() {
  const [dashboard, setDashboard] = useState(null)
  const [error, setError] = useState('')

  useEffect(() => {
    adminApi.dashboard().then(setDashboard).catch((apiError) => setError(apiError.message))
  }, [])

  if (!dashboard && !error) return <Loading />
  return (
    <section>
      <div className="page-heading">
        <div><p className="eyebrow">Vue d’ensemble</p><h2>Tableau de bord</h2></div>
      </div>
      <ErrorMessage message={error} />
      {dashboard && <>
        <div className="card-grid">
          <article className="stat-card"><span>Clients</span><strong>{dashboard.totalCustomers}</strong><small>{dashboard.activeCustomers} actifs</small></article>
          <article className="stat-card"><span>Comptes</span><strong>{dashboard.totalAccounts}</strong><small>{dashboard.frozenAccounts} gelés</small></article>
          <article className="stat-card"><span>Comptes clôturés</span><strong>{dashboard.closedAccounts}</strong><small>{dashboard.activeAccounts} actifs</small></article>
          <article className="stat-card"><span>Opérations</span><strong>{dashboard.totalTransactions}</strong><small>données simulées</small></article>
        </div>
        <article className="panel">
          <h3>Opérations récentes</h3>
          {dashboard.recentTransactions.length === 0 ? <Empty /> : (
            <div className="table-wrap"><table><thead><tr><th>Date</th><th>Compte</th><th>Description</th><th>Montant</th></tr></thead>
              <tbody>{dashboard.recentTransactions.map((transaction) => <tr key={transaction.transactionId}>
                <td>{formatDate(transaction.bookingDate)}</td><td>{transaction.accountId}</td><td>{transaction.description}</td>
                <td className={transaction.direction === 'CREDIT' ? 'credit' : 'debit'}>{transaction.direction === 'CREDIT' ? '+' : '−'} {formatMoney(transaction.amount, transaction.currency)}</td>
              </tr>)}</tbody></table></div>
          )}
        </article>
      </>}
    </section>
  )
}
