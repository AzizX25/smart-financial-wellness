import { useEffect, useState } from 'react'
import { Link, NavLink, Outlet, useLocation } from 'react-router-dom'
import { profile } from '../data/demoData'
import Icon from './Icon'
import { DemoBanner } from './Ui'

const navigation = [
  { to: '/tableau-de-bord', label: 'Accueil', icon: 'home' },
  { to: '/transactions', label: 'Transactions', icon: 'transactions' },
  { to: '/connexion-bancaire', label: 'Ma banque', icon: 'bank' },
  { to: '/planification-salaire', label: 'Mon budget', icon: 'plan' },
  { to: '/objectifs', label: 'Mes objectifs', icon: 'target' },
  { to: '/previsions', label: 'Prévisions', icon: 'chart' },
  { to: '/profil', label: 'Profil et réglages', icon: 'user' },
]

export default function AppShell() {
  const location = useLocation()
  const [navigationOpen, setNavigationOpen] = useState(false)

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
          <Icon name={navigationOpen ? 'close' : 'menu'} size={24} />
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
          <p className="sidebar__label">Votre espace</p>
          {navigation.map((item) => (
            <NavLink
              key={item.to}
              className={({ isActive }) => `nav-link${isActive ? ' nav-link--active' : ''}`}
              to={item.to}
            >
              <Icon name={item.icon} size={21} />
              <span>{item.label}</span>
            </NavLink>
          ))}
        </nav>

        <div className="sidebar__support">
          <Icon name="shield" size={24} />
          <div><strong>Vos données restent privées</strong><span>Accès bancaire en lecture seule</span></div>
        </div>

        <div className="sidebar__profile">
          <span className="avatar" aria-hidden="true">AT</span>
          <div><strong>{profile.fullName}</strong><span>{profile.email}</span></div>
          <Link className="sidebar__logout" to="/connexion" aria-label="Se déconnecter">
            <Icon name="logout" size={20} />
          </Link>
        </div>
      </aside>

      <main className="main-content" id="contenu-principal">
        <div className="main-content__topbar">
          <DemoBanner />
          <div className="topbar__actions">
            <button className="icon-button" type="button" aria-label="Voir les notifications">
              <Icon name="bell" size={21} />
              <span className="notification-dot" aria-hidden="true" />
            </button>
            <Link className="topbar__signout" to="/connexion">
              <Icon name="logout" size={19} />
              <span>Se déconnecter</span>
            </Link>
          </div>
        </div>
        <Outlet />
      </main>
    </div>
  )
}
