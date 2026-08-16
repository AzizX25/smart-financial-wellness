import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import { adminApi } from './api/adminApi'
import { ADMIN_TOKEN_KEY } from './api/authSession'

vi.mock('./api/adminApi', () => ({
  adminApi: {
    login: vi.fn(), dashboard: vi.fn(), customers: vi.fn(), customer: vi.fn(), createCustomer: vi.fn(),
    updateCustomer: vi.fn(), resetCustomerPassword: vi.fn(), customerAction: vi.fn(), accounts: vi.fn(),
    account: vi.fn(), createAccount: vi.fn(), updateAccount: vi.fn(), accountAction: vi.fn(),
    transactions: vi.fn(), createTransaction: vi.fn(),
  },
}))

function renderApp(path = '/') {
  window.history.pushState({}, '', path)
  return render(<App />)
}

const emptyPage = { items: [], page: 0, size: 10, totalElements: 0, totalPages: 0, first: true, last: true }
const dashboard = {
  totalCustomers: 2, activeCustomers: 2, inactiveCustomers: 0,
  totalAccounts: 3, activeAccounts: 3, frozenAccounts: 0, closedAccounts: 0,
  totalTransactions: 8, recentTransactions: [],
}

describe('console administrateur', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    adminApi.dashboard.mockResolvedValue(dashboard)
    adminApi.customers.mockResolvedValue(emptyPage)
    adminApi.accounts.mockResolvedValue(emptyPage)
  })

  it('protège les écrans et ouvre le tableau de bord après connexion', async () => {
    adminApi.login.mockResolvedValue({ accessToken: 'signed-admin-token' })
    renderApp('/clients')
    expect(screen.getByRole('heading', { name: /Administration de la banque simulée/i })).toBeInTheDocument()

    fireEvent.change(screen.getByLabelText(/Nom d’utilisateur/i), { target: { value: 'admin.demo' } })
    fireEvent.change(screen.getByLabelText(/Mot de passe/i), { target: { value: 'AdminBank2026!' } })
    fireEvent.click(screen.getByRole('button', { name: 'Se connecter' }))

    await screen.findByRole('heading', { name: 'Clients' })
    expect(sessionStorage.getItem(ADMIN_TOKEN_KEY)).toBe('signed-admin-token')
  })

  it('affiche une erreur contrôlée et efface le mot de passe si la connexion échoue', async () => {
    adminApi.login.mockRejectedValue(new Error('Identifiants incorrects'))
    renderApp()
    fireEvent.change(screen.getByLabelText(/Nom d’utilisateur/i), { target: { value: 'admin.demo' } })
    const password = screen.getByLabelText(/Mot de passe/i)
    fireEvent.change(password, { target: { value: 'incorrect' } })
    fireEvent.click(screen.getByRole('button', { name: 'Se connecter' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Identifiants incorrects')
    expect(password).toHaveValue('')
  })

  it('rend les clients et leurs comptes renvoyés par le backend', async () => {
    sessionStorage.setItem(ADMIN_TOKEN_KEY, 'token')
    adminApi.customers.mockResolvedValue({
      ...emptyPage,
      items: [{ customerId: 'MOCK-CUST-900', displayName: 'Client test', username: 'client.test', countryCode: 'TN', status: 'ACTIVE' }],
      totalElements: 1, totalPages: 1,
    })
    const customerView = renderApp('/clients')
    expect(await screen.findByText('Client test')).toBeInTheDocument()
    customerView.unmount()

    adminApi.account.mockResolvedValue({
      accountId: 'MOCK-ACC-900', customerId: 'MOCK-CUST-900', displayName: 'Compte test', accountType: 'CHECKING',
      currency: 'TND', currentBalance: 50, availableBalance: 50, status: 'ACTIVE',
    })
    adminApi.transactions.mockResolvedValue(emptyPage)
    renderApp('/comptes/MOCK-ACC-900')
    expect(await screen.findByRole('heading', { name: 'Compte test' })).toBeInTheDocument()
    expect(screen.getAllByText(/50,000/).length).toBeGreaterThan(0)
  })

  it('crée une opération confirmée puis recharge le solde et l’historique', async () => {
    sessionStorage.setItem(ADMIN_TOKEN_KEY, 'token')
    const initialAccount = {
      accountId: 'MOCK-ACC-901', customerId: 'MOCK-CUST-901', displayName: 'Compte opérations',
      accountType: 'CHECKING', currency: 'TND', currentBalance: 0, availableBalance: 0, status: 'ACTIVE',
    }
    const creditedAccount = { ...initialAccount, currentBalance: 25, availableBalance: 25 }
    adminApi.account.mockResolvedValueOnce(initialAccount).mockResolvedValue(creditedAccount)
    adminApi.transactions.mockResolvedValueOnce(emptyPage).mockResolvedValue({
      ...emptyPage,
      items: [{ transactionId: 'TX-1', bookingDate: '2026-08-02', amount: 25, direction: 'CREDIT', currency: 'TND', description: 'Crédit de test' }],
      totalElements: 1, totalPages: 1,
    })
    adminApi.createTransaction.mockResolvedValue({ transactionId: 'TX-1' })
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    renderApp('/comptes/MOCK-ACC-901')
    await screen.findByRole('heading', { name: 'Compte opérations' })

    fireEvent.change(screen.getByLabelText('Montant'), { target: { value: '25' } })
    fireEvent.change(screen.getByLabelText('Description'), { target: { value: 'Crédit de test' } })
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer l’opération' }))

    await waitFor(() => expect(adminApi.createTransaction).toHaveBeenCalledWith(
      'MOCK-ACC-901', expect.objectContaining({ amount: 25, direction: 'CREDIT' }),
    ))
    expect(await screen.findByText('Crédit de test')).toBeInTheDocument()
    expect(adminApi.account).toHaveBeenCalledTimes(2)
  })
})
