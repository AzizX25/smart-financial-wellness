import { useEffect, useState } from 'react'
import { Link, useLocation } from 'react-router-dom'
import AuthLayout from '../components/auth/AuthLayout'
import Icon from '../components/Icon'
import { resendVerificationEmail } from '../services/authApi'

const RESEND_COOLDOWN_SECONDS = 30

function maskEmail(email) {
  const [localPart, domain] = email.split('@')
  if (!localPart || !domain) {
    return ''
  }
  const visibleStart = localPart.slice(0, 1)
  const visibleEnd = localPart.length > 2 ? localPart.slice(-1) : ''
  return `${visibleStart}${'•'.repeat(Math.max(3, localPart.length - 2))}${visibleEnd}@${domain}`
}

export default function VerificationPendingPage() {
  const location = useLocation()
  const initialEmail = typeof location.state?.email === 'string' ? location.state.email : ''
  const [email, setEmail] = useState(initialEmail)
  const [isResending, setIsResending] = useState(false)
  const [cooldown, setCooldown] = useState(0)
  const [notice, setNotice] = useState(null)

  useEffect(() => {
    if (cooldown <= 0) {
      return undefined
    }
    const timer = window.setInterval(() => {
      setCooldown((value) => Math.max(0, value - 1))
    }, 1000)
    return () => window.clearInterval(timer)
  }, [cooldown])

  async function handleResend() {
    if (isResending || cooldown > 0 || !email.trim()) {
      return
    }
    setIsResending(true)
    setNotice(null)
    try {
      await resendVerificationEmail(email.trim().toLowerCase())
      setNotice({
        tone: 'success',
        message: 'Si cette adresse peut recevoir un e-mail, un nouveau lien vient d’être envoyé.',
      })
      setCooldown(RESEND_COOLDOWN_SECONDS)
    } catch {
      setNotice({
        tone: 'error',
        message: 'L’envoi n’a pas pu être demandé pour le moment. Veuillez réessayer.',
      })
    } finally {
      setIsResending(false)
    }
  }

  return (
    <AuthLayout
      description="Votre compte a bien été créé, mais il n’est pas encore actif."
      eyebrow="Une dernière étape"
      icon="mail"
      title="Consultez votre boîte e-mail"
    >
      <div className="auth-instruction">
        <span className="auth-instruction__number">1</span>
        <p>Ouvrez l’e-mail de vérification envoyé par Mon Équilibre.</p>
      </div>
      <div className="auth-instruction">
        <span className="auth-instruction__number">2</span>
        <p>Cliquez sur le lien sécurisé pour activer votre compte.</p>
      </div>

      {email ? (
        <p className="masked-email" aria-label={`E-mail envoyé à ${maskEmail(email)}`}>
          <Icon name="mail" size={19} />
          {maskEmail(email)}
        </p>
      ) : (
        <label className="auth-field auth-field--standalone">
          <span>Adresse e-mail</span>
          <input
            autoComplete="email"
            inputMode="email"
            onChange={(event) => setEmail(event.target.value)}
            placeholder="votre.nom@exemple.tn"
            type="email"
            value={email}
          />
        </label>
      )}

      {notice && (
        <div
          className={`auth-alert auth-alert--${notice.tone}`}
          role={notice.tone === 'error' ? 'alert' : 'status'}
        >
          <Icon name={notice.tone === 'success' ? 'check' : 'alert'} size={20} />
          <span>{notice.message}</span>
        </div>
      )}

      <button
        className="auth-button auth-button--primary"
        disabled={isResending || cooldown > 0 || !email.trim()}
        onClick={handleResend}
        type="button"
      >
        {isResending && <span className="auth-spinner" aria-hidden="true" />}
        {isResending
          ? 'Envoi en cours…'
          : cooldown > 0
            ? `Réessayer dans ${cooldown} s`
            : 'Renvoyer l’e-mail de vérification'}
      </button>

      {import.meta.env.DEV && (
        <p className="mailpit-hint">
          En développement local, consultez les messages dans{' '}
          <a href="http://localhost:8025" rel="noreferrer" target="_blank">Mailpit</a>.
        </p>
      )}

      <p className="auth-switch">
        Vous avez déjà vérifié votre adresse ? <Link to="/connexion">Retour à la connexion</Link>
      </p>
    </AuthLayout>
  )
}
