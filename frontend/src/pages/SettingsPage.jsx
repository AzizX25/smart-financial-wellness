import { useEffect, useRef, useState } from 'react'
import { useAuth } from '../auth/AuthContext'
import BankConnectionSection from '../components/BankConnectionSection'
import Icon from '../components/Icon'
import ProfileEditor from '../components/ProfileEditor'
import { Badge, Card, InlineNotice, PageHeader } from '../components/Ui'
import { consentSettings } from '../data/demoData'
import { ApiError } from '../services/apiClient'
import { getProfile } from '../services/profileApi'

const accountStatuses = {
  ACTIVE: 'Compte actif',
  DELETED: 'Compte supprimé',
  DELETION_PENDING: 'Suppression en cours',
  PENDING_VERIFICATION: 'Vérification en attente',
  SUSPENDED: 'Compte suspendu',
}

const themes = { DARK: 'Sombre', LIGHT: 'Clair', SYSTEM: 'Système' }
const textSizes = { LARGE: 'Grand', MEDIUM: 'Moyen', SMALL: 'Petit' }
const priorities = { HIGH: 'Haute', LOW: 'Basse', MEDIUM: 'Moyenne' }

function initials(profile) {
  return [profile?.firstName, profile?.lastName]
    .filter(Boolean)
    .map((value) => value.trim().slice(0, 1).toUpperCase())
    .join('') || profile?.displayName?.slice(0, 2).toUpperCase() || '—'
}

export default function SettingsPage() {
  const { user, refreshUser } = useAuth()
  const [profile, setProfile] = useState(null)
  const [loading, setLoading] = useState(true)
  const [profileError, setProfileError] = useState('')
  const [feedback, setFeedback] = useState('')
  const [editing, setEditing] = useState(false)
  const mounted = useRef(false)

  useEffect(() => {
    let active = true
    mounted.current = true
    getProfile()
      .then((response) => {
        if (active) {
          setProfile(response)
          setProfileError('')
        }
      })
      .catch((error) => {
        if (active) {
          setProfileError(error instanceof ApiError && error.status === 0
            ? 'Le profil ne peut pas être chargé car le service est inaccessible.'
            : 'Le profil ne peut pas être chargé pour le moment.')
        }
      })
      .finally(() => {
        if (active) {
          setLoading(false)
        }
      })
    return () => {
      active = false
      mounted.current = false
    }
  }, [])

  function saved(response) {
    setProfile(response)
    setEditing(false)
    setFeedback('Votre profil et vos préférences ont été enregistrés.')
    refreshUser().catch(() => {
      if (mounted.current) setFeedback('Profil enregistré. Le nom de votre session sera actualisé à la prochaine connexion.')
    })
  }

  const identity = profile || user || {}
  const interfacePreferences = profile?.interfacePreferences
  const financialPreferences = profile?.financialPreferences
  const notifications = profile?.notificationPreferences || []

  return (
    <div className="page">
      <PageHeader
        eyebrow="Votre espace personnel"
        title="Profil et réglages"
        description="Gérez votre profil, vos préférences et la connexion à la banque simulée."
        action={<button className="button button--primary" type="button" disabled={loading || !profile?.interfacePreferences || !profile?.financialPreferences || editing} onClick={() => { setFeedback(''); setEditing(true) }}>Modifier mon profil</button>}
      />

      {feedback && <InlineNotice live tone="success">{feedback}</InlineNotice>}
      {profileError && <InlineNotice live tone="warning" icon="alert">{profileError}</InlineNotice>}

      {editing && <ProfileEditor profile={profile} onSaved={saved} onCancel={() => setEditing(false)} />}

      <div className="settings-grid">
        <Card className="profile-card">
          {loading ? (
            <div className="profile-loading" role="status"><span className="auth-spinner" /> Chargement du profil…</div>
          ) : (
            <>
              <div className="profile-card__identity">
                <span className="avatar avatar--large">{initials(identity)}</span>
                <div>
                  <h2>{identity.displayName || 'Nom non renseigné'}</h2>
                  <p>{identity.email || 'Adresse e-mail indisponible'}</p>
                  <Badge tone={identity.accountStatus === 'ACTIVE' ? 'teal' : 'neutral'}>
                    {accountStatuses[identity.accountStatus] || 'État indisponible'}
                  </Badge>
                </div>
              </div>
              <dl className="detail-list">
                <div><dt>Prénom</dt><dd>{identity.firstName || 'Non renseigné'}</dd></div>
                <div><dt>Nom</dt><dd>{identity.lastName || 'Non renseigné'}</dd></div>
                <div><dt>E-mail vérifié</dt><dd>{identity.emailVerified ? 'Oui' : 'Non'}</dd></div>
              </dl>
            </>
          )}
        </Card>

        <Card>
          <div className="card-heading"><div><p className="eyebrow">Préférences financières</p><h2>Mes repères</h2></div><span className="soft-icon"><Icon name="wallet" /></span></div>
          <dl className="detail-list">
            <div><dt>Devise principale</dt><dd>{financialPreferences?.primaryCurrency || 'Non renseignée'}</dd></div>
            <div><dt>Début du mois budgétaire</dt><dd>{financialPreferences?.monthStartDay ? `Jour ${financialPreferences.monthStartDay}` : 'Non renseigné'}</dd></div>
            <div><dt>Seuil de solde faible</dt><dd>{financialPreferences?.lowBalanceThreshold ?? 'Non renseigné'}</dd></div>
            <div><dt>Montants masqués à l’accueil</dt><dd>{financialPreferences?.hideHomeAmounts ? 'Oui' : 'Non'}</dd></div>
          </dl>
        </Card>

        <Card>
          <div className="card-heading"><div><p className="eyebrow">Interface</p><h2>Confort d’utilisation</h2></div><span className="soft-icon"><Icon name="user" /></span></div>
          <dl className="detail-list">
            <div><dt>Langue</dt><dd>{interfacePreferences?.languageTag || 'Non renseignée'}</dd></div>
            <div><dt>Fuseau horaire</dt><dd>{interfacePreferences?.timeZone || 'Non renseigné'}</dd></div>
            <div><dt>Thème</dt><dd>{themes[interfacePreferences?.theme] || 'Non renseigné'}</dd></div>
            <div><dt>Taille du texte</dt><dd>{textSizes[interfacePreferences?.textSize] || 'Non renseignée'}</dd></div>
            <div><dt>Contraste renforcé</dt><dd>{interfacePreferences?.highContrast ? 'Oui' : 'Non'}</dd></div>
            <div><dt>Animations réduites</dt><dd>{interfacePreferences?.reducedMotion ? 'Oui' : 'Non'}</dd></div>
          </dl>
        </Card>

        <Card>
          <div className="card-heading"><div><p className="eyebrow">Notifications</p><h2>Préférences enregistrées</h2></div><span className="soft-icon"><Icon name="bell" /></span></div>
          {notifications.length === 0 ? (
            <p className="muted">Aucune préférence de notification enregistrée.</p>
          ) : (
            <div className="preference-list">
              {notifications.map((notification) => (
                <div key={notification.id}>
                  <span><strong>{notification.eventType}</strong><small>{notification.channel} · priorité {priorities[notification.minimumPriority] || notification.minimumPriority}</small></span>
                  <Badge tone={notification.enabled ? 'mint' : 'neutral'}>{notification.enabled ? 'Activée' : 'Désactivée'}</Badge>
                </div>
              ))}
            </div>
          )}
        </Card>
      </div>

      <div className="settings-section">
        <BankConnectionSection />
      </div>

      <Card className="settings-section">
        <div className="card-heading"><div><p className="eyebrow">Consentements</p><h2>Vos choix de confidentialité</h2></div><Badge tone="blue">Prototype</Badge></div>
        <div className="consent-list">
          {consentSettings.map((consent) => <div key={consent.label}><span><strong>{consent.label}</strong><small>{consent.detail}</small></span><Badge tone="mint">{consent.status}</Badge></div>)}
        </div>
      </Card>

      <Card className="data-control-card">
        <div><span className="soft-icon"><Icon name="shield" /></span><div><p className="eyebrow">Vos données, vos droits</p><h2>Exporter ou supprimer mes données</h2><p className="muted">Ces workflows restent simulés dans cette étape du frontend.</p></div></div>
        <div className="button-row">
          <button className="button button--secondary" type="button" onClick={() => setFeedback("Demande d’export simulée : aucun fichier réel n’a été généré.")}>Demander un export</button>
          <button className="button button--danger-quiet" type="button" onClick={() => setFeedback("Demande de suppression simulée : aucune donnée n’a été modifiée.")}>Demander la suppression du compte</button>
        </div>
      </Card>
    </div>
  )
}
