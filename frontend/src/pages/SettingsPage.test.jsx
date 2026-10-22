import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useAuth } from '../auth/AuthContext'
import { ApiError } from '../services/apiClient'
import { getProfile, updateProfile } from '../services/profileApi'
import SettingsPage from './SettingsPage'

vi.mock('../auth/AuthContext', () => ({
  useAuth: vi.fn(),
}))

vi.mock('../services/profileApi', () => ({ getProfile: vi.fn(), updateProfile: vi.fn() }))
vi.mock('../components/BankConnectionSection', () => ({
  default: () => <div>Section bancaire test</div>,
}))

const backendProfile = {
  id: '10000000-0000-0000-0000-000000000099',
  email: 'noura.profile@example.test',
  firstName: 'Noura',
  lastName: 'Ben Salah',
  displayName: 'Noura Profile',
  accountStatus: 'ACTIVE',
  identityStatus: 'ACTIVE',
  emailVerified: true,
  interfacePreferences: {
    languageTag: 'fr-TN',
    timeZone: 'Africa/Tunis',
    theme: 'SYSTEM',
    textSize: 'MEDIUM',
    highContrast: false,
    reducedMotion: true,
  },
  financialPreferences: {
    primaryCurrency: 'TND',
    monthStartDay: 1,
    lowBalanceThreshold: 75,
    hideHomeAmounts: false,
  },
  notificationPreferences: [],
}

const refreshUser = vi.fn()

describe('profil authentifié', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    getProfile.mockResolvedValue(backendProfile)
    updateProfile.mockResolvedValue(backendProfile)
    refreshUser.mockResolvedValue({ displayName: 'Noura Profile' })
    useAuth.mockReturnValue({
      user: { displayName: 'Valeur de session', email: 'session@example.test' },
      refreshUser,
    })
  })

  async function edit() {
    const view = render(<SettingsPage />)
    const button = screen.getByRole('button', { name: 'Modifier mon profil' })
    await waitFor(() => expect(button).toBeEnabled())
    fireEvent.click(button)
    return view
  }

  it('remplace les données fictives par le profil renvoyé par le backend', async () => {
    render(<SettingsPage />)

    expect(screen.getByText('Chargement du profil…')).toBeInTheDocument()
    expect(await screen.findByRole('heading', { name: 'Noura Profile' })).toBeInTheDocument()
    expect(screen.getByText('noura.profile@example.test')).toBeInTheDocument()
    expect(screen.getByText('Africa/Tunis')).toBeInTheDocument()
    expect(screen.getByText('Jour 1')).toBeInTheDocument()
    expect(screen.queryByText('Amel Trabelsi')).not.toBeInTheDocument()
  })

  it('enregistre les champs autorisés, conserve les autres préférences et affiche la réponse serveur', async () => {
    updateProfile.mockResolvedValue({
      ...backendProfile,
      displayName: 'Nom confirmé par le serveur',
      financialPreferences: { ...backendProfile.financialPreferences, primaryCurrency: 'EUR' },
      interfacePreferences: { ...backendProfile.interfacePreferences, timeZone: 'Europe/Paris' },
    })
    await edit()
    expect(screen.queryByRole('textbox', { name: /e-mail/i })).not.toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Nom affiché'), { target: { value: ' Noura B. ' } })
    fireEvent.change(screen.getByLabelText('Devise principale'), { target: { value: 'eur' } })
    fireEvent.change(screen.getByLabelText('Fuseau horaire'), { target: { value: 'Europe/Paris' } })
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer mon profil' }))

    expect(await screen.findByRole('heading', { name: 'Nom confirmé par le serveur' })).toBeInTheDocument()
    expect(updateProfile).toHaveBeenCalledWith({
      firstName: 'Noura',
      lastName: 'Ben Salah',
      displayName: 'Noura B.',
      interfacePreferences: { ...backendProfile.interfacePreferences, timeZone: 'Europe/Paris' },
      financialPreferences: { ...backendProfile.financialPreferences, primaryCurrency: 'EUR' },
    })
    expect(screen.getByText('noura.profile@example.test')).toBeInTheDocument()
    expect(screen.getByText('EUR')).toBeInTheDocument()
    expect(screen.getByText('Europe/Paris')).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('enregistrés')
    expect(refreshUser).toHaveBeenCalledTimes(1)
  })

  it('permet de vider un nom facultatif sans envoyer une chaîne vide', async () => {
    await edit()
    fireEvent.change(screen.getByLabelText('Prénom'), { target: { value: '   ' } })
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer mon profil' }))
    await waitFor(() => expect(updateProfile).toHaveBeenCalledWith(expect.objectContaining({ firstName: null })))
  })

  it('refuse les champs invalides avant toute mutation et place le focus sur la première erreur', async () => {
    await edit()
    fireEvent.change(screen.getByLabelText('Nom affiché'), { target: { value: '  ' } })
    fireEvent.change(screen.getByLabelText('Devise principale'), { target: { value: 'TN' } })
    fireEvent.change(screen.getByLabelText('Langue enregistrée'), { target: { value: 'invalid_locale' } })
    fireEvent.change(screen.getByLabelText('Fuseau horaire'), { target: { value: 'Unknown/Zone' } })
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer mon profil' }))
    expect(updateProfile).not.toHaveBeenCalled()
    expect(screen.getByLabelText('Nom affiché')).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByLabelText('Nom affiché')).toHaveFocus()
    expect(screen.getByLabelText('Devise principale')).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByLabelText('Langue enregistrée')).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByLabelText('Fuseau horaire')).toHaveAttribute('aria-invalid', 'true')
  })

  it('conserve les valeurs saisies après un refus serveur sans modifier le profil affiché', async () => {
    updateProfile.mockRejectedValue(new ApiError(400, 'Internal validation detail', { 'interfacePreferences.timeZone': 'Invalid' }))
    await edit()
    fireEvent.change(screen.getByLabelText('Nom affiché'), { target: { value: 'Noura modifiée' } })
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer mon profil' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('n’a pas pu être enregistré')
    expect(screen.getByLabelText('Fuseau horaire')).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByLabelText('Nom affiché')).toHaveValue('Noura modifiée')
    expect(screen.getByRole('heading', { name: 'Noura Profile' })).toBeInTheDocument()
    expect(screen.queryByText('Internal validation detail')).not.toBeInTheDocument()
    expect(refreshUser).not.toHaveBeenCalled()
  })

  it('annule sans mutation et sans conserver un brouillon à la réouverture', async () => {
    await edit()
    fireEvent.change(screen.getByLabelText('Nom affiché'), { target: { value: 'Brouillon' } })
    fireEvent.click(screen.getByRole('button', { name: 'Annuler' }))
    expect(updateProfile).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: 'Modifier mon profil' }))
    expect(screen.getByLabelText('Nom affiché')).toHaveValue('Noura Profile')
  })

  it('bloque les soumissions répétées et ignore une réponse après avoir quitté la page', async () => {
    let resolve
    updateProfile.mockReturnValue(new Promise((done) => { resolve = done }))
    const view = await edit()
    const form = screen.getByRole('form', { name: 'Modifier mon profil' })
    fireEvent.submit(form)
    fireEvent.submit(form)
    expect(updateProfile).toHaveBeenCalledTimes(1)
    expect(screen.getByRole('button', { name: 'Enregistrement…' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Annuler' })).toBeDisabled()
    view.unmount()
    await act(async () => { resolve(backendProfile) })
    expect(refreshUser).not.toHaveBeenCalled()
  })

  it('ne présente pas une indisponibilité comme un enregistrement réussi', async () => {
    updateProfile.mockRejectedValue(new ApiError(0, 'Unavailable'))
    await edit()
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer mon profil' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('ne sont pas enregistrées')
    expect(screen.getByRole('button', { name: 'Enregistrer mon profil' })).toBeEnabled()
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
  })

  it('ne permet pas la modification si le profil ne peut pas être chargé', async () => {
    getProfile.mockRejectedValue(new ApiError(0, 'Unavailable'))
    render(<SettingsPage />)
    expect(await screen.findByRole('alert')).toHaveTextContent('service est inaccessible')
    expect(screen.getByRole('button', { name: 'Modifier mon profil' })).toBeDisabled()
    expect(updateProfile).not.toHaveBeenCalled()
  })

  it('confirme un profil sauvegardé même si le rafraîchissement du nom de session échoue', async () => {
    refreshUser.mockRejectedValue(new ApiError(0, 'Unavailable'))
    await edit()
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer mon profil' }))
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('prochaine connexion'))
    expect(screen.queryByRole('form', { name: 'Modifier mon profil' })).not.toBeInTheDocument()
  })
})
