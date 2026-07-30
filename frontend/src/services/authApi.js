const configuredBaseUrl = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080'
const API_BASE_URL = configuredBaseUrl.replace(/\/+$/, '')

export class AuthApiError extends Error {
  constructor(status, message, fieldErrors = {}) {
    super(message)
    this.name = 'AuthApiError'
    this.status = status
    this.fieldErrors = fieldErrors
  }
}

async function request(path, body) {
  let response

  try {
    response = await fetch(`${API_BASE_URL}${path}`, {
      method: 'POST',
      credentials: 'include',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(body),
    })
  } catch {
    throw new AuthApiError(0, 'Le service est momentanément inaccessible.')
  }

  let payload = {}
  try {
    payload = await response.json()
  } catch {
    payload = {}
  }

  if (!response.ok) {
    throw new AuthApiError(
      response.status,
      typeof payload.message === 'string' ? payload.message : 'La demande n’a pas pu être traitée.',
      payload.fieldErrors && typeof payload.fieldErrors === 'object' ? payload.fieldErrors : {},
    )
  }

  return payload
}

export async function registerAccount(registration) {
  const payload = await request('/api/auth/register', registration)
  return { user: payload.user }
}

export function resendVerificationEmail(email) {
  return request('/api/auth/email-verification/resend', { email })
}

export function confirmEmailVerification(token) {
  return request('/api/auth/email-verification/confirm', { token })
}
