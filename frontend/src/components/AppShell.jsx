import { NavLink, Outlet } from 'react-router-dom'
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
  return (
    <div className="app-shell">
      <a className="skip-link" href="#contenu-principal">Aller au contenu principal</a>
      <aside className="sidebar">
        <NavLink className="brand" to="/tableau-de-bord" aria-label="Mon Équilibre — accueil">
          <span className="brand__mark"><Icon name="wallet" size={24} /></span>
          <span><strong>Mon Équilibre</strong><small>Votre assistant financier</small></span>
        </NavLink>

        <nav className="sidebar__nav" aria-label="Navigation principale">
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
        </div>
      </aside>

      <main className="main-content" id="contenu-principal">
        <div className="main-content__topbar">
          <DemoBanner />
          <button className="icon-button" type="button" aria-label="Voir les notifications">
            <Icon name="bell" size={21} />
            <span className="notification-dot" aria-hidden="true" />
          </button>
        </div>
        <Outlet />
      </main>
    </div>
  )
}
