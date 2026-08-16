import { useState } from 'react'
import { ErrorMessage } from '../components/Ui'

export default function LoginPage({ onLogin }) {
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  async function submit(event) {
    event.preventDefault()
    setError('')
    setLoading(true)
    try {
      await onLogin({ username, password })
    } catch (apiError) {
      setError(apiError.message || 'Connexion impossible.')
    } finally {
      setPassword('')
      setLoading(false)
    }
  }

  return (
    <main className="login-shell">
      <section className="login-card">
        <div className="brand-mark">BS</div>
        <h1>Administration de la banque simulée</h1>
        <p className="environment-banner">Banque simulée – environnement de développement uniquement</p>
        <ErrorMessage message={error} />
        <form onSubmit={submit}>
          <label>Nom d’utilisateur
            <input value={username} onChange={(event) => setUsername(event.target.value)} required />
          </label>
          <label>Mot de passe
            <input type="password" value={password} onChange={(event) => setPassword(event.target.value)} required />
          </label>
          <button className="button primary" disabled={loading}>
            {loading ? 'Connexion…' : 'Se connecter'}
          </button>
        </form>
      </section>
    </main>
  )
}
