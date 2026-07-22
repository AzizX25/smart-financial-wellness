import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import Icon from '../components/Icon'
import { DemoBanner, InlineNotice } from '../components/Ui'

export default function LoginPage() {
  const navigate = useNavigate()
  const [showPassword, setShowPassword] = useState(false)

  function handleSubmit(event) {
    event.preventDefault()
    navigate('/tableau-de-bord')
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

        <Link className="text-link text-link--light" to="/banque-demo">
          Voir aussi la maquette Banque Atlas Démo <Icon name="arrow" size={18} />
        </Link>
      </section>

      <section className="login-panel" aria-labelledby="login-title">
        <div className="login-panel__inner">
          <DemoBanner compact />
          <p className="eyebrow">Heureuse de vous revoir</p>
          <h2 id="login-title">Connexion</h2>
          <p className="muted">Accédez à votre espace financier personnel.</p>

          <form className="form-stack" onSubmit={handleSubmit}>
            <label className="field">
              <span>Adresse e-mail</span>
              <input
                autoComplete="email"
                name="email"
                placeholder="votre.nom@exemple.tn"
                required
                type="email"
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
              <label className="checkbox-label"><input type="checkbox" /> Se souvenir de moi</label>
              <a className="text-link" href="#mot-de-passe-oublie">Mot de passe oublié ?</a>
            </div>

            <button className="button button--primary button--wide" type="submit">
              Se connecter <Icon name="arrow" size={19} />
            </button>
          </form>

          <InlineNotice>
            Pour la démonstration, saisissez n'importe quel e-mail et mot de passe.
            Aucune information n'est envoyée ni enregistrée.
          </InlineNotice>

          <p className="login-panel__signup">
            Nouveau sur Mon Équilibre ? <a className="text-link" href="#inscription">Créer un compte</a>
          </p>
        </div>
      </section>
    </main>
  )
}
