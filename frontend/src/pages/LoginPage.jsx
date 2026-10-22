import { useState } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import Icon from '../components/Icon'
import { DemoBanner, InlineNotice } from '../components/Ui'
import { ApiError } from '../services/apiClient'

export default function LoginPage() {
  const location = useLocation()
  const navigate = useNavigate()
  const { login } = useAuth()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [showPassword, setShowPassword] = useState(false)

  async function handleSubmit(event) {
    event.preventDefault()
    if (isSubmitting) {
      return
    }

    setIsSubmitting(true)
    setError('')
    try {
      const user = await login({
        email: email.trim().toLowerCase(),
        password,
      })
      if (!user.emailVerified) {
        navigate('/verify-email-sent', {
          replace: true,
          state: { email: user.email },
        })
        return
      }
      const destination = typeof location.state?.from === 'string'
        ? location.state.from
        : '/tableau-de-bord'
      navigate(destination, { replace: true })
    } catch (requestError) {
      setError(
        requestError instanceof ApiError && requestError.status === 401
          ? 'Adresse e-mail ou mot de passe incorrect.'
          : requestError instanceof ApiError && requestError.status === 0
            ? 'Le service est momentanément inaccessible. Réessayez plus tard.'
            : 'La connexion n’a pas pu être effectuée.',
      )
    } finally {
      setPassword('')
      setIsSubmitting(false)
    }
  }

  return (
    <main className="login-page">
      <section className="login-story" aria-labelledby="login-story-title">
        <Link className="brand brand--light" to="/connexion">
          <span className="brand__mark"><Icon name="wallet" size={24} /></span>
          <span><strong>Mon Équilibre</strong><small>Smart Financial Wellness</small></span>
        </Link>

        <div className="login-story__content">
          <p className="eyebrow eyebrow--light">Votre argent, plus simple à comprendre</p>
          <h1 id="login-story-title">Avancez sereinement, un choix à la fois.</h1>
          <p>
            Retrouvez vos comptes, votre budget et vos objectifs dans un espace
            clair, pensé pour vous guider sans jargon.
          </p>
          <ul className="trust-list">
            <li><Icon name="check" size={19} /> Des explications faciles à comprendre</li>
            <li><Icon name="check" size={19} /> Un accès bancaire strictement en lecture seule</li>
            <li><Icon name="check" size={19} /> Des recommandations informatives, jamais des promesses</li>
          </ul>
        </div>

        <p className="login-story__security">La connexion bancaire simulée est disponible après authentification.</p>
      </section>

      <section className="login-panel" aria-labelledby="login-title">
        <div className="login-panel__inner">
          <DemoBanner compact />
          <p className="eyebrow">Heureuse de vous revoir</p>
          <h2 id="login-title">Connexion</h2>
          <p className="muted">Accédez à votre espace financier personnel.</p>

          {error && <div className="auth-alert auth-alert--error" role="alert"><Icon name="alert" size={20} /><span>{error}</span></div>}

          <form className="form-stack" onSubmit={handleSubmit}>
            <label className="field">
              <span>Adresse e-mail</span>
              <input
                autoComplete="email"
                name="email"
                placeholder="votre.nom@exemple.tn"
                required
                type="email"
                value={email}
                onChange={(event) => {
                  setEmail(event.target.value)
                  setError('')
                }}
              />
            </label>

            <label className="field">
              <span>Mot de passe</span>
              <span className="password-field">
                <input
                  autoComplete="current-password"
                  name="password"
                  placeholder="Votre mot de passe"
                  required
                  type={showPassword ? 'text' : 'password'}
                  value={password}
                  onChange={(event) => {
                    setPassword(event.target.value)
                    setError('')
                  }}
                />
                <button
                  type="button"
                  onClick={() => setShowPassword((visible) => !visible)}
                  aria-label={showPassword ? 'Masquer le mot de passe' : 'Afficher le mot de passe'}
                >
                  {showPassword ? 'Masquer' : 'Afficher'}
                </button>
              </span>
            </label>

            <div className="form-row form-row--between">
              <span />
              <a className="text-link" href="#mot-de-passe-oublie">Mot de passe oublié ?</a>
            </div>

            <button className="button button--primary button--wide" disabled={isSubmitting} type="submit">
              {isSubmitting ? 'Connexion en cours…' : 'Se connecter'} <Icon name="arrow" size={19} />
            </button>
          </form>

          <InlineNotice>
            Votre session utilise un jeton d’accès temporaire et un cookie de renouvellement HttpOnly.
          </InlineNotice>

          <p className="login-panel__signup">
            Nouveau sur Mon Équilibre ? <Link className="text-link" to="/register">Créer un compte</Link>
          </p>
        </div>
      </section>
    </main>
  )
}
