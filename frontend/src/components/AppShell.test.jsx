import { fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useAuth } from '../auth/AuthContext'
import AppShell from './AppShell'

vi.mock('../auth/AuthContext', () => ({ useAuth: vi.fn() }))

function renderShell() {
  return render(
    <MemoryRouter initialEntries={['/tableau-de-bord']}>
      <Routes>
        <Route element={<AppShell />}>
          <Route path="/tableau-de-bord" element={<h1>Accueil financier</h1>} />
          <Route path="/transactions" element={<h1>Mes transactions</h1>} />
        </Route>
      </Routes>
    </MemoryRouter>,
  )
}

describe('navigation organisée', () => {
  beforeEach(() => {
    useAuth.mockReturnValue({ user: { firstName: 'Amine', lastName: 'Demo' }, logout: vi.fn() })
  })

  it('regroupe les pages et indique la route active sans changer les destinations', () => {
    renderShell()
    const navigation = screen.getByRole('navigation', { name: 'Navigation principale' })
    const daily = within(navigation).getByRole('group', { name: 'Au quotidien' })
    const projects = within(navigation).getByRole('group', { name: 'Mes projets' })
    const account = within(navigation).getByRole('group', { name: 'Mon compte' })

    expect(within(daily).getByRole('link', { name: 'Accueil' })).toHaveAttribute('aria-current', 'page')
    expect(within(daily).getByRole('link', { name: 'Transactions' })).toHaveAttribute('href', '/transactions')
    expect(within(projects).getByRole('link', { name: 'Mon budget' })).toHaveAttribute('href', '/planification-salaire')
    expect(within(projects).getByRole('link', { name: 'Prévisions' })).toHaveAttribute('href', '/previsions')
    expect(within(account).getByRole('link', { name: 'Profil et réglages' })).toHaveAttribute('href', '/profil')
    expect(within(navigation).getAllByRole('link')).toHaveLength(8)
  })

  it('ferme le menu après navigation et garde la fermeture au clavier', () => {
    renderShell()
    fireEvent.click(screen.getByRole('button', { name: 'Ouvrir le menu' }))
    const menu = screen.getByRole('button', { name: 'Fermer le menu', expanded: true })
    expect(menu).toHaveAttribute('aria-controls', 'navigation-principale')

    fireEvent.click(screen.getByRole('link', { name: 'Transactions' }))
    expect(screen.getByRole('heading', { name: 'Mes transactions' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Ouvrir le menu' })).toHaveAttribute('aria-expanded', 'false')

    fireEvent.click(screen.getByRole('button', { name: 'Ouvrir le menu' }))
    fireEvent.keyDown(window, { key: 'Escape' })
    expect(screen.getByRole('button', { name: 'Ouvrir le menu' })).toHaveAttribute('aria-expanded', 'false')
  })
})
