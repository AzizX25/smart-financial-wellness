import { useEffect, useRef, useState } from 'react'
import { ApiError } from '../services/apiClient'
import { updateProfile } from '../services/profileApi'
import { Card, InlineNotice } from './Ui'

const labels = {
  firstName: 'Prénom',
  lastName: 'Nom',
  displayName: 'Nom affiché',
  primaryCurrency: 'Devise principale',
  languageTag: 'Langue enregistrée',
  timeZone: 'Fuseau horaire',
}

function validate(values) {
  const errors = {}
  for (const name of ['firstName', 'lastName']) {
    if (values[name].trim().length > 100) errors[name] = 'Utilisez au maximum 100 caractères.'
  }
  if (!values.displayName.trim() || values.displayName.trim().length > 160) {
    errors.displayName = 'Saisissez un nom affiché de 1 à 160 caractères.'
  }
  if (!/^[A-Z]{3}$/.test(values.primaryCurrency.trim())) {
    errors.primaryCurrency = 'Utilisez un code de devise à trois lettres, par exemple TND.'
  }
  try {
    const tag = values.languageTag.trim()
    if (!tag || tag.length > 35 || Intl.getCanonicalLocales(tag).length !== 1) throw new Error()
  } catch {
    errors.languageTag = 'Utilisez un code de langue valide, par exemple fr-TN.'
  }
  try {
    const zone = values.timeZone.trim()
    if (!zone || zone.length > 80) throw new Error()
    new Intl.DateTimeFormat('fr', { timeZone: zone }).format()
  } catch {
    errors.timeZone = 'Utilisez un fuseau horaire valide, par exemple Africa/Tunis.'
  }
  return errors
}

export default function ProfileEditor({ profile, onSaved, onCancel }) {
  const [values, setValues] = useState(() => ({
    firstName: profile.firstName || '',
    lastName: profile.lastName || '',
    displayName: profile.displayName || '',
    primaryCurrency: profile.financialPreferences.primaryCurrency,
    languageTag: profile.interfacePreferences.languageTag,
    timeZone: profile.interfacePreferences.timeZone,
  }))
  const [errors, setErrors] = useState({})
  const [requestError, setRequestError] = useState('')
  const [saving, setSaving] = useState(false)
  const submitting = useRef(false)
  const mounted = useRef(false)
  const form = useRef(null)

  useEffect(() => {
    mounted.current = true
    return () => { mounted.current = false }
  }, [])

  function change(event) {
    const { name, value } = event.target
    setValues((current) => ({ ...current, [name]: name === 'primaryCurrency' ? value.toUpperCase() : value }))
    setErrors((current) => ({ ...current, [name]: undefined }))
    setRequestError('')
  }

  async function save(event) {
    event.preventDefault()
    if (submitting.current) return
    const validation = validate(values)
    setErrors(validation)
    if (Object.keys(validation).length) {
      form.current.elements.namedItem(Object.keys(validation)[0])?.focus()
      return
    }
    submitting.current = true
    setSaving(true)
    setRequestError('')
    try {
      const response = await updateProfile({
        firstName: values.firstName.trim() || null,
        lastName: values.lastName.trim() || null,
        displayName: values.displayName.trim(),
        interfacePreferences: {
          ...profile.interfacePreferences,
          languageTag: values.languageTag.trim(),
          timeZone: values.timeZone.trim(),
        },
        financialPreferences: {
          ...profile.financialPreferences,
          primaryCurrency: values.primaryCurrency.trim(),
        },
      })
      if (mounted.current) onSaved(response)
    } catch (error) {
      if (!mounted.current) return
      if (error instanceof ApiError) {
        const fieldErrors = {}
        for (const field of Object.keys(error.fieldErrors)) {
          const name = field.split('.').at(-1)
          if (Object.hasOwn(labels, name)) fieldErrors[name] = 'Vérifiez cette valeur.'
        }
        setErrors(fieldErrors)
        setRequestError(error.status === 0
          ? 'Le service est inaccessible. Vos modifications ne sont pas enregistrées.'
          : 'Le profil n’a pas pu être enregistré. Vérifiez les informations puis réessayez.')
      } else {
        setRequestError('Le profil n’a pas pu être enregistré. Réessayez dans un moment.')
      }
    } finally {
      submitting.current = false
      if (mounted.current) setSaving(false)
    }
  }

  function field(name, props = {}) {
    const errorId = `profile-${name}-error`
    return (
      <label className="field">
        <span id={`profile-${name}-label`}>{labels[name]}</span>
        <input
          name={name}
          value={values[name]}
          onChange={change}
          aria-invalid={Boolean(errors[name])}
          aria-labelledby={`profile-${name}-label`}
          aria-describedby={errors[name] ? errorId : undefined}
          {...props}
        />
        {errors[name] && <small className="field-error" id={errorId}>{errors[name]}</small>}
      </label>
    )
  }

  return (
    <Card className="profile-editor">
      <div>
        <p className="eyebrow">Informations personnelles</p>
        <h2 id="profile-editor-title">Modifier mon profil</h2>
        <p className="muted">Votre adresse e-mail et l’état du compte ne sont pas modifiables ici.</p>
      </div>
      {requestError && <InlineNotice live tone="warning">{requestError}</InlineNotice>}
      <form ref={form} onSubmit={save} noValidate aria-labelledby="profile-editor-title" aria-busy={saving}>
        <fieldset disabled={saving} className="profile-editor__fields">
          <legend className="sr-only">Profil et préférences personnelles</legend>
          {field('firstName', { autoComplete: 'given-name', maxLength: 100 })}
          {field('lastName', { autoComplete: 'family-name', maxLength: 100 })}
          {field('displayName', { autoComplete: 'nickname', maxLength: 160, required: true })}
          {field('primaryCurrency', { maxLength: 3, required: true })}
          {field('languageTag', { placeholder: 'fr-TN', maxLength: 35, required: true })}
          {field('timeZone', { placeholder: 'Africa/Tunis', maxLength: 80, required: true })}
        </fieldset>
        <p className="muted profile-editor__note">Changer la devise ne convertit aucun montant. Les opérations restent séparées par devise. La langue est enregistrée ; l’interface reste en français.</p>
        <div className="button-row">
          <button className="button button--primary" disabled={saving} type="submit">{saving ? 'Enregistrement…' : 'Enregistrer mon profil'}</button>
          <button className="button button--secondary" disabled={saving} onClick={onCancel} type="button">Annuler</button>
        </div>
      </form>
    </Card>
  )
}
