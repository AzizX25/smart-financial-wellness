import { useEffect, useState } from 'react'
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import Icon from './Icon'
import { DemoBanner } from './Ui'

const navigation = [
  {
    label: 'Au quotidien',
    items: [
      { to: '/tableau-de-bord', label: 'Accueil', icon: 'home' },
      { to: '/transactions', label: 'Transactions', icon: 'transactions' },
      { to: '/categories', label: 'Catégories', icon: 'tag' },
      { to: '/connexion-bancaire', label: 'Ma banque', icon: 'bank' },
    ],
  },
  {
    label: 'Mes projets',
    items: [
      { to: '/planification-salaire', label: 'Mon budget', icon: 'plan' },
      { to: '/objectifs', label: 'Mes objectifs', icon: 'target' },
      { to: '/previsions', label: 'Prévisions', icon: 'chart' },
    ],
  },
  {
    label: 'Mon compte',
    items: [{ to: '/profil', label: 'Profil et réglages', icon: 'user' }],
  },
]

export default function AppShell() {
  const location = useLocation()
  const navigate = useNavigate()
  const { logout, user } = useAuth()
  const [navigationOpen, setNavigationOpen] = useState(false)
  const displayName = user?.displayName?.trim() || [user?.firstName, user?.lastName].filter(Boolean).join(' ')
  const initials = [user?.firstName, user?.lastName]
    .filter(Boolean)
    .map((name) => name.trim().slice(0, 1).toUpperCase())
    .join('') || displayName?.slice(0, 2).toUpperCase() || '-'

  async function handleLogout() {
    await logout()
    navigate('/connexion', { replace: true })
  }

  useEffect(() => {
    setNavigationOpen(false)
  }, [location.pathname])

  useEffect(() => {
    if (!navigationOpen) {
      return undefined
    }

    function closeOnEscape(event) {
      if (event.key === 'Escape') {
        setNavigationOpen(false)
      }
    }

    window.addEventListener('keydown', closeOnEscape)
    return () => window.removeEventListener('keydown', closeOnEscape)
  }, [navigationOpen])

  return (
    <div className="app-shell">
      <a className="skip-link" href="#contenu-principal">Aller au contenu principal</a>
      <header className="mobile-header">
        <NavLink className="brand" to="/tableau-de-bord" aria-label="Mon Équilibre — accueil">
          <span className="brand__mark"><Icon name="wallet" size={22} /></span>
          <span><strong>Mon Équilibre</strong><small>Assistant financier</small></span>
        </NavLink>
        <button
          aria-controls="navigation-principale"
          aria-expanded={navigationOpen}
          aria-label={navigationOpen ? 'Fermer le menu' : 'Ouvrir le menu'}
          className="mobile-menu-button"
          onClick={() => setNavigationOpen((open) => !open)}
          type="button"
        >
          <Icon name={navigationOpen ? 'close' : 'menu'} size={22} />
          <span className="mobile-menu-button__label" aria-hidden="true">{navigationOpen ? 'Fermer' : 'Menu'}</span>
        </button>
      </header>

      {navigationOpen && (
        <button
          aria-label="Fermer le menu"
          className="sidebar-backdrop"
          onClick={() => setNavigationOpen(false)}
          type="button"
        />
      )}

      <aside className={`sidebar${navigationOpen ? ' sidebar--open' : ''}`} id="navigation-principale">
        <div className="sidebar__header">
          <NavLink className="brand" to="/tableau-de-bord" aria-label="Mon Équilibre — accueil">
            <span className="brand__mark"><Icon name="wallet" size={24} /></span>
            <span><strong>Mon Équilibre</strong><small>Votre assistant financier</small></span>
          </NavLink>
          <button
            aria-label="Fermer le menu"
            className="mobile-menu-button mobile-menu-button--close"
            onClick={() => setNavigationOpen(false)}
            type="button"
          >
            <Icon name="close" size={24} />
          </button>
        </div>

        <nav className="sidebar__nav" aria-label="Navigation principale">
          {navigation.map((group) => (
            <div className="sidebar__group" role="group" aria-label={group.label} key={group.label}>
              <p className="sidebar__label">{group.label}</p>
              {group.items.map((item) => (
                <NavLink
                  key={item.to}
                  className={({ isActive }) => `nav-link${isActive ? ' nav-link--active' : ''}`}
                  to={item.to}
                >
                  <Icon name={item.icon} size={21} />
                  <span>{item.label}</span>
                </NavLink>
              ))}
            </div>
          ))}
        </nav>

        <div className="sidebar__support">
          <Icon name="shield" size={24} />
          <div><strong>Vos données restent privées</strong><span>Accès bancaire en lecture seule</span></div>
        </div>

        <div className="sidebar__profile">
          <span className="avatar" aria-hidden="true">{initials}</span>
          <div><strong>{displayName || 'Nom non renseigné'}</strong><span>{user?.email || ''}</span></div>
          <button className="sidebar__logout" onClick={handleLogout} type="button" aria-label="Se déconnecter">
            <Icon name="logout" size={20} />
          </button>
        </div>
      </aside>

      <main className="main-content" id="contenu-principal">
        <div className="main-content__topbar">
          <DemoBanner />
          <div className="topbar__actions">
            <button className="topbar__signout" onClick={handleLogout} type="button">
              <Icon name="logout" size={19} />
              <span>Se déconnecter</span>
            </button>
          </div>
        </div>
        <Outlet />
      </main>
    </div>
  )
}
