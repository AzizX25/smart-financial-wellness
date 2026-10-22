import { Link } from 'react-router-dom'
import Icon from '../Icon'

export default function AuthLayout({
  children,
  description,
  eyebrow,
  icon = 'shield',
  title,
  tone = 'primary',
  wide = false,
}) {
  return (
    <main className="auth-flow">
      <a className="skip-link" href="#auth-content">Aller au contenu principal</a>
      <div className="auth-shape auth-shape--one" aria-hidden="true" />
      <div className="auth-shape auth-shape--two" aria-hidden="true" />
      <div className="auth-shape auth-shape--three" aria-hidden="true" />

      <section className={`auth-card${wide ? ' auth-card--wide' : ''}`} id="auth-content">
        <Link className="auth-brand" to="/connexion" aria-label="Mon Équilibre — connexion">
          <span className="auth-brand__mark" aria-hidden="true">
            <i />
            <i />
            <i />
            <i />
          </span>
          <span>
            <strong>Mon Équilibre</strong>
            <small>Smart Financial Wellness</small>
          </span>
        </Link>

        <div className={`auth-icon auth-icon--${tone}`} aria-hidden="true">
          <Icon name={icon} size={29} />
        </div>
        {eyebrow && <p className="auth-eyebrow">{eyebrow}</p>}
        <h1>{title}</h1>
        {description && <p className="auth-description">{description}</p>}

        {children}

        <p className="auth-security-note">
          <Icon name="lock" size={17} />
          Vos informations d’authentification sont protégées et ne sont jamais affichées.
        </p>
      </section>
    </main>
  )
}
