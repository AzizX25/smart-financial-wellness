import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react'
import { ApiError, clearAccessToken, restoreSession, setSessionInvalidHandler } from '../services/apiClient'
import { getCurrentUser, loginAccount, logoutAccount } from '../services/authApi'

const AuthContext = createContext(null)

export function AuthProvider({ children }) {
  const [status, setStatus] = useState('checking')
  const [user, setUser] = useState(null)
  const [error, setError] = useState('')
  const [attempt, setAttempt] = useState(0)

  const invalidateSession = useCallback(() => {
    clearAccessToken()
    setUser(null)
    setStatus('anonymous')
    setError('')
  }, [])

  useEffect(() => setSessionInvalidHandler(invalidateSession), [invalidateSession])

  useEffect(() => {
    let active = true
    setStatus('checking')
    setError('')

    restoreSession()
      .then(() => getCurrentUser())
      .then((currentUser) => {
        if (active) {
          setUser(currentUser)
          setStatus('authenticated')
        }
      })
      .catch((sessionError) => {
        if (!active) {
          return
        }
        if (sessionError instanceof ApiError && sessionError.status === 401) {
          invalidateSession()
          return
        }
        setUser(null)
        setStatus('error')
        setError('Impossible de vérifier votre session pour le moment.')
      })

    return () => {
      active = false
    }
  }, [attempt, invalidateSession])

  const login = useCallback(async (credentials) => {
    const payload = await loginAccount(credentials)
    setUser(payload.user)
    setStatus('authenticated')
    setError('')
    return payload.user
  }, [])

  const logout = useCallback(async () => {
    try {
      await logoutAccount()
    } finally {
      invalidateSession()
    }
  }, [invalidateSession])

  const refreshUser = useCallback(async () => {
    const currentUser = await getCurrentUser()
    setUser(currentUser)
    return currentUser
  }, [])

  const value = useMemo(() => ({
    error,
    login,
    logout,
    refreshUser,
    retry: () => setAttempt((value) => value + 1),
    status,
    user,
  }), [error, login, logout, refreshUser, status, user])

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth() {
  const context = useContext(AuthContext)
  if (!context) {
    throw new Error('useAuth must be used within AuthProvider')
  }
  return context
}
