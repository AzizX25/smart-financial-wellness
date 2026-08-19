const configuredBaseUrl = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080'
export const API_BASE_URL = configuredBaseUrl.replace(/\/+$/, '')

let accessToken = null
let refreshInFlight = null
let sessionInvalidHandler = () => {}

export class ApiError extends Error {
  constructor(status, message, fieldErrors = {}, path = '', code = null) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.fieldErrors = fieldErrors
    this.path = path
    this.code = code
  }
}

export function setAccessToken(token) {
  accessToken = typeof token === 'string' && token ? token : null
}

export function clearAccessToken() {
  accessToken = null
}

export function setSessionInvalidHandler(handler) {
  sessionInvalidHandler = typeof handler === 'function' ? handler : () => {}
  return () => {
    sessionInvalidHandler = () => {}
  }
}

async function parseResponse(response) {
  if (response.status === 204) {
    return null
  }

  const contentType = response.headers.get('Content-Type') || ''
  if (!contentType.includes('application/json')) {
    return null
  }

  try {
    return await response.json()
  } catch {
    return null
  }
}

async function performRequest(path, options = {}) {
  const {
    authenticated = true,
    body,
    headers: providedHeaders,
    method = 'GET',
  } = options
  const headers = new Headers(providedHeaders)

  if (body !== undefined) {
    headers.set('Content-Type', 'application/json')
  }
  if (authenticated && accessToken) {
    headers.set('Authorization', `Bearer ${accessToken}`)
  }

  let response
  try {
    response = await fetch(`${API_BASE_URL}${path}`, {
      method,
      credentials: 'include',
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    })
  } catch {
    throw new ApiError(0, 'Le service est momentanément inaccessible.')
  }

  const payload = await parseResponse(response)
  if (!response.ok) {
    throw new ApiError(
      response.status,
      typeof payload?.message === 'string'
        ? payload.message
        : 'La demande n’a pas pu être traitée.',
      payload?.fieldErrors && typeof payload.fieldErrors === 'object'
        ? payload.fieldErrors
        : {},
      typeof payload?.path === 'string' ? payload.path : path,
      typeof payload?.code === 'string' ? payload.code : null,
    )
  }

  return payload
}

async function requestNewAccessToken() {
  if (!refreshInFlight) {
    refreshInFlight = performRequest('/api/auth/refresh', {
      authenticated: false,
      method: 'POST',
    }).finally(() => {
      refreshInFlight = null
    })
  }
  const payload = await refreshInFlight
  setAccessToken(payload?.accessToken)
  return payload
}

export async function restoreSession({ notifyOnUnauthorized = false } = {}) {
  try {
    return await requestNewAccessToken()
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) {
      clearAccessToken()
      if (notifyOnUnauthorized) {
        sessionInvalidHandler()
      }
    }
    throw error
  }
}

export async function apiRequest(path, options = {}) {
  const authenticated = options.authenticated !== false
  try {
    return await performRequest(path, { ...options, authenticated })
  } catch (error) {
    if (!(error instanceof ApiError) || error.status !== 401 || !authenticated) {
      throw error
    }

    try {
      await restoreSession({ notifyOnUnauthorized: true })
    } catch (refreshError) {
      if (refreshError instanceof ApiError && refreshError.status === 401) {
        throw error
      }
      throw refreshError
    }

    return performRequest(path, { ...options, authenticated: true })
  }
}

export function publicApiRequest(path, options = {}) {
  return performRequest(path, { ...options, authenticated: false })
}
