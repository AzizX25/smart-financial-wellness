import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import AuthLayout from '../components/auth/AuthLayout'
import Icon from '../components/Icon'
import { AuthApiError, registerAccount } from '../services/authApi'

const initialValues = {
  firstName: '',
  lastName: '',
  email: '',
  password: '',
  passwordConfirmation: '',
}

const utf8Length = (value) => new TextEncoder().encode(value).length

function validate(values) {
  const errors = {}
  const firstName = values.firstName.trim()
  const lastName = values.lastName.trim()
  const email = values.email.trim()

  if (!firstName) {
    errors.firstName = 'Saisissez votre prénom.'
  } else if (firstName.length > 100) {
    errors.firstName = 'Le prénom ne peut pas dépasser 100 caractères.'
  }

  if (!lastName) {
    errors.lastName = 'Saisissez votre nom.'
  } else if (lastName.length > 100) {
    errors.lastName = 'Le nom ne peut pas dépasser 100 caractères.'
  }

  if (!email) {
    errors.email = 'Saisissez votre adresse e-mail.'
  } else if (email.length > 320 || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
    errors.email = 'Saisissez une adresse e-mail valide.'
  }

  const passwordBytes = utf8Length(values.password)
  if (values.password.length < 12) {
    errors.password = 'Utilisez au moins 12 caractères.'
  } else if (passwordBytes > 72) {
    errors.password = 'Le mot de passe dépasse la limite sécurisée de 72 octets.'
  }

  if (!values.passwordConfirmation) {
    errors.passwordConfirmation = 'Confirmez votre mot de passe.'
  } else if (values.passwordConfirmation !== values.password) {
    errors.passwordConfirmation = 'Les deux mots de passe ne correspondent pas.'
  }

  return errors
}

export default function RegisterPage() {
  const navigate = useNavigate()
  const [values, setValues] = useState(initialValues)
  const [touched, setTouched] = useState({})
  const [errors, setErrors] = useState({})
  const [requestError, setRequestError] = useState('')
  const [showPassword, setShowPassword] = useState(false)
  const [showConfirmation, setShowConfirmation] = useState(false)
  const [isSubmitting, setIsSubmitting] = useState(false)

  function updateField(event) {
    const { name, value } = event.target
    const nextValues = { ...values, [name]: value }
    setValues(nextValues)
    if (touched[name]) {
      setErrors(validate(nextValues))
    }
    setRequestError('')
  }

  function blurField(event) {
    const nextTouched = { ...touched, [event.target.name]: true }
    setTouched(nextTouched)
    setErrors(validate(values))
  }

  async function handleSubmit(event) {
    event.preventDefault()
    if (isSubmitting) {
      return
    }

    const validationErrors = validate(values)
    setTouched({
      firstName: true,
      lastName: true,
      email: true,
      password: true,
      passwordConfirmation: true,
    })
    setErrors(validationErrors)
    if (Object.keys(validationErrors).length > 0) {
      return
    }

    setIsSubmitting(true)
    setRequestError('')
    const normalizedEmail = values.email.trim().toLowerCase()

    try {
      await registerAccount({
        firstName: values.firstName.trim(),
        lastName: values.lastName.trim(),
        displayName: `${values.firstName.trim()} ${values.lastName.trim()}`,
        email: normalizedEmail,
        password: values.password,
      })
      navigate('/verify-email-sent', {
        replace: true,
        state: { email: normalizedEmail },
      })
    } catch (error) {
      if (error instanceof AuthApiError) {
        const backendFields = Object.fromEntries(
          Object.keys(error.fieldErrors).map((field) => [
            field,
            field === 'password'
              ? 'Le mot de passe ne respecte pas les exigences indiquées.'
              : 'Vérifiez cette valeur.',
          ]),
        )
        setErrors((current) => ({ ...current, ...backendFields }))
        setRequestError(
          error.status === 409
            ? 'Un compte utilise déjà cette adresse e-mail.'
            : error.status === 0
              ? 'Impossible de joindre le service. Vérifiez votre connexion puis réessayez.'
              : 'La création du compte n’a pas abouti. Vérifiez les informations saisies.',
        )
      } else {
        setRequestError('Une erreur inattendue est survenue. Veuillez réessayer.')
      }
    } finally {
      setIsSubmitting(false)
    }
  }

  return (
    <AuthLayout
      description="Créez votre espace personnel. Vous devrez ensuite confirmer votre adresse e-mail."
      eyebrow="Bienvenue"
      icon="user"
      title="Créer votre compte"
      wide
    >
      {requestError && (
        <div className="auth-alert auth-alert--error" role="alert">
          <Icon name="alert" size={20} />
          <span>{requestError}</span>
        </div>
      )}

      <form className="auth-form" noValidate onSubmit={handleSubmit}>
        <div className="auth-form__row">
          <AuthField
            autoComplete="given-name"
            error={touched.firstName && errors.firstName}
            label="Prénom"
            name="firstName"
            onBlur={blurField}
            onChange={updateField}
            value={values.firstName}
          />
          <AuthField
            autoComplete="family-name"
            error={touched.lastName && errors.lastName}
            label="Nom"
            name="lastName"
            onBlur={blurField}
            onChange={updateField}
            value={values.lastName}
          />
        </div>

        <AuthField
          autoComplete="email"
          error={touched.email && errors.email}
          inputMode="email"
          label="Adresse e-mail"
          name="email"
          onBlur={blurField}
          onChange={updateField}
          placeholder="votre.nom@exemple.tn"
          type="email"
          value={values.email}
        />

        <PasswordField
          autoComplete="new-password"
          error={touched.password && errors.password}
          label="Mot de passe"
          name="password"
          onBlur={blurField}
          onChange={updateField}
          onToggle={() => setShowPassword((visible) => !visible)}
          show={showPassword}
          value={values.password}
        />

        <ul className="password-requirements" id="password-requirements">
          <li className={values.password.length >= 12 ? 'is-met' : ''}>
            <Icon name="check" size={16} /> Au moins 12 caractères
          </li>
          <li className={utf8Length(values.password) <= 72 ? 'is-met' : ''}>
            <Icon name="check" size={16} /> Maximum 72 octets en UTF-8
          </li>
          <li><Icon name="info" size={16} /> Une phrase longue et unique est plus facile à retenir.</li>
        </ul>

        <PasswordField
          autoComplete="new-password"
          error={touched.passwordConfirmation && errors.passwordConfirmation}
          label="Confirmation du mot de passe"
          name="passwordConfirmation"
          onBlur={blurField}
          onChange={updateField}
          onToggle={() => setShowConfirmation((visible) => !visible)}
          show={showConfirmation}
          value={values.passwordConfirmation}
        />

        <button
          className="auth-button auth-button--primary"
          disabled={isSubmitting}
          type="submit"
        >
          {isSubmitting ? (
            <>
              <span className="auth-spinner" aria-hidden="true" />
              Création en cours…
            </>
          ) : (
            <>
              Créer mon compte
              <Icon name="arrow" size={19} />
            </>
          )}
        </button>
      </form>

      <p className="auth-switch">
        Vous avez déjà un compte ? <Link to="/connexion">Se connecter</Link>
      </p>
    </AuthLayout>
  )
}

function AuthField({ error, label, name, type = 'text', ...inputProps }) {
  const errorId = `${name}-error`
  return (
    <label className={`auth-field${error ? ' auth-field--error' : ''}`}>
      <span>{label}</span>
      <input
        aria-describedby={error ? errorId : undefined}
        aria-invalid={Boolean(error)}
        name={name}
        type={type}
        {...inputProps}
      />
      {error && <small id={errorId}>{error}</small>}
    </label>
  )
}

function PasswordField({ error, label, name, onToggle, show, ...inputProps }) {
  const errorId = `${name}-error`
  return (
    <label className={`auth-field${error ? ' auth-field--error' : ''}`}>
      <span>{label}</span>
      <span className="auth-password">
        <input
          aria-describedby={error ? errorId : undefined}
          aria-invalid={Boolean(error)}
          name={name}
          type={show ? 'text' : 'password'}
          {...inputProps}
        />
        <button
          aria-label={show ? `Masquer ${label.toLowerCase()}` : `Afficher ${label.toLowerCase()}`}
          onClick={onToggle}
          type="button"
        >
          {show ? 'Masquer' : 'Afficher'}
        </button>
      </span>
      {error && <small id={errorId}>{error}</small>}
    </label>
  )
}
