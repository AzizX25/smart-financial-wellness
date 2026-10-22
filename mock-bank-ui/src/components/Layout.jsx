import { Link, useLocation } from 'wouter'

export default function Layout({ onLogout, children }) {
  const [location] = useLocation()
  const active = (path) => location === path || (path !== '/' && location.startsWith(path))
  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div>
          <div className="brand-mark">BS</div>
          <h1>Banque simulée</h1>
          <p>Administration des données de test</p>
        </div>
        <nav aria-label="Navigation principale">
          <Link href="/" className={active('/') ? 'active' : ''}>Tableau de bord</Link>
          <Link href="/clients" className={active('/clients') ? 'active' : ''}>Clients</Link>
          <Link href="/comptes" className={active('/comptes') ? 'active' : ''}>Comptes</Link>
        </nav>
        <button className="button secondary" onClick={onLogout}>Se déconnecter</button>
      </aside>
      <main className="main-content">
        <div className="environment-banner">
          Banque simulée – environnement de développement uniquement
        </div>
        {children}
      </main>
    </div>
  )
}
