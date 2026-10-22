import { Navigate, Outlet, useLocation } from 'react-router-dom'
import { useAuth } from './AuthContext'

export default function ProtectedRoute() {
  const location = useLocation()
  const { error, retry, status } = useAuth()

  if (status === 'checking') {
    return <main className="session-state" role="status">Vérification de votre session…</main>
  }

  if (status === 'error') {
    return (
      <main className="session-state" role="alert">
        <p>{error}</p>
        <button className="button button--primary" onClick={retry} type="button">Réessayer</button>
      </main>
    )
  }

  if (status !== 'authenticated') {
    return <Navigate replace state={{ from: location.pathname }} to="/connexion" />
  }

  return <Outlet />
}
