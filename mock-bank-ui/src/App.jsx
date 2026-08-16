import { useEffect, useState } from 'react'
import { Redirect, Route, Switch } from 'wouter'
import { adminApi } from './api/adminApi'
import { clearAdminToken, getAdminToken, saveAdminToken } from './api/authSession'
import Layout from './components/Layout'
import AccountDetailPage from './pages/AccountDetailPage'
import AccountsPage from './pages/AccountsPage'
import CustomerDetailPage from './pages/CustomerDetailPage'
import CustomersPage from './pages/CustomersPage'
import DashboardPage from './pages/DashboardPage'
import LoginPage from './pages/LoginPage'

export default function App() {
  const [authenticated, setAuthenticated] = useState(Boolean(getAdminToken()))

  useEffect(() => {
    const unauthorized = () => setAuthenticated(false)
    window.addEventListener('mock-bank:unauthorized', unauthorized)
    return () => window.removeEventListener('mock-bank:unauthorized', unauthorized)
  }, [])

  async function login(credentials) {
    const response = await adminApi.login(credentials)
    saveAdminToken(response.accessToken)
    setAuthenticated(true)
  }

  function logout() {
    clearAdminToken()
    setAuthenticated(false)
  }

  if (!authenticated) {
    return <LoginPage onLogin={login} />
  }

  return (
    <Layout onLogout={logout}>
      <Switch>
        <Route path="/" component={DashboardPage} />
        <Route path="/clients" component={CustomersPage} />
        <Route path="/clients/:customerId">{(params) => <CustomerDetailPage customerId={params.customerId} />}</Route>
        <Route path="/comptes" component={AccountsPage} />
        <Route path="/comptes/:accountId">{(params) => <AccountDetailPage accountId={params.accountId} />}</Route>
        <Route><Redirect to="/" /></Route>
      </Switch>
    </Layout>
  )
}
