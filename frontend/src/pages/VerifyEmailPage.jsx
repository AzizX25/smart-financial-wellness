import { useLayoutEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import AuthLayout from '../components/auth/AuthLayout'
import Icon from '../components/Icon'
import { confirmEmailVerification } from '../services/authApi'

function takeTokenFromFragment() {
  const fragment = window.location.hash.startsWith('#')
    ? window.location.hash.slice(1)
    : window.location.hash
  const token = new URLSearchParams(fragment).get('token')
  window.history.replaceState(
    window.history.state,
    '',
    `${window.location.pathname}${window.location.search}`,
  )
  return token
}

export default function VerifyEmailPage() {
  const confirmationRef = useRef(null)
  const [state, setState] = useState('loading')

  useLayoutEffect(() => {
    if (!confirmationRef.current) {
      const token = takeTokenFromFragment()
      confirmationRef.current = token
        ? confirmEmailVerification(token)
        : Promise.reject(new Error('missing verification token'))
    }

    let active = true
    confirmationRef.current
      .then(() => {
        if (active) {
          setState('success')
        }
      })
      .catch(() => {
        if (active) {
          setState('error')
        }
      })
    return () => {
      active = false
    }
  }, [])

  if (state === 'loading') {
    return (
      <AuthLayout
        description="Veuillez patienter pendant que nous confirmons votre lien sécurisé."
        eyebrow="Activation"
        icon="shield"
        title="Vérification de votre adresse e-mail…"
      >
        <div className="verification-progress" role="status">
          <span className="auth-spinner auth-spinner--large" aria-hidden="true" />
          <span>Vérification en cours</span>
        </div>
      </AuthLayout>
    )
  }

  if (state === 'success') {
    return (
      <AuthLayout
        description="Votre adresse e-mail a été vérifiée avec succès. Votre espace personnel est maintenant actif."
        eyebrow="Vérification réussie"
        icon="check"
        title="Votre compte est activé"
        tone="success"
      >
        <div className="auth-alert auth-alert--success" role="status">
          <Icon name="shield" size={21} />
          <span>Vous pouvez désormais vous connecter en toute sécurité.</span>
        </div>
        <Link className="auth-button auth-button--primary" to="/connexion">
          Se connecter <Icon name="arrow" size={19} />
        </Link>
      </AuthLayout>
    )
  }

  return (
    <AuthLayout
      description="Ce lien peut avoir expiré, avoir déjà été utilisé ou être incomplet."
      eyebrow="Lien indisponible"
      icon="alert"
      title="Ce lien de vérification n’est plus valide."
      tone="error"
    >
      <div className="auth-alert auth-alert--error" role="alert">
        <Icon name="info" size={21} />
        <span>Pour votre sécurité, aucun détail supplémentaire n’est affiché.</span>
      </div>
      <div className="auth-actions">
        <Link className="auth-button auth-button--primary" to="/verify-email-sent">
          Demander un nouvel e-mail
        </Link>
        <Link className="auth-button auth-button--secondary" to="/connexion">
          Retour à la connexion
        </Link>
      </div>
    </AuthLayout>
  )
}
