import {
  ApiError,
  apiRequest,
  clearAccessToken,
  publicApiRequest,
  setAccessToken,
} from './apiClient'

export { ApiError as AuthApiError }

export async function registerAccount(registration) {
  const payload = await publicApiRequest('/api/auth/register', {
    method: 'POST',
    body: registration,
  })
  return { user: payload.user }
}

export async function loginAccount(credentials) {
  const payload = await publicApiRequest('/api/auth/login', {
    method: 'POST',
    body: credentials,
  })
  setAccessToken(payload.accessToken)
  return payload
}

export function getCurrentUser() {
  return apiRequest('/api/auth/me')
}

export async function logoutAccount() {
  try {
    await apiRequest('/api/auth/logout', { method: 'POST' })
  } finally {
    clearAccessToken()
  }
}

export function resendVerificationEmail(email) {
  return publicApiRequest('/api/auth/email-verification/resend', {
    method: 'POST',
    body: { email },
  })
}

export function confirmEmailVerification(token) {
  return publicApiRequest('/api/auth/email-verification/confirm', {
    method: 'POST',
    body: { token },
  })
}
