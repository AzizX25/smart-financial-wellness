import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../services/apiClient'
import {
  addGoalContribution,
  archiveGoal,
  changeGoalStatus,
  createGoal,
  deleteGoalContribution,
  getGoalAllocations,
  listGoalContributions,
  listGoals,
  replaceGoalAllocations,
  simulateGoal,
  updateGoal,
} from '../services/goalsApi'
import GoalsPage from './GoalsPage'

vi.mock('../services/goalsApi', () => ({
  addGoalContribution: vi.fn(),
  archiveGoal: vi.fn(),
  changeGoalStatus: vi.fn(),
  createGoal: vi.fn(),
  deleteGoalContribution: vi.fn(),
  getGoalAllocations: vi.fn(),
  listGoalContributions: vi.fn(),
  listGoals: vi.fn(),
  replaceGoalAllocations: vi.fn(),
  simulateGoal: vi.fn(),
  updateGoal: vi.fn(),
}))

const today = new Date()
const DEFAULT_MONTH = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}`

function shiftMonth(value, offset) {
  const [year, month] = value.split('-').map(Number)
  const shifted = new Date(year, month - 1 + offset, 1)
  return `${shifted.getFullYear()}-${String(shifted.getMonth() + 1).padStart(2, '0')}`
}

const GOAL_ID = '11111111-1111-1111-1111-111111111111'
const SECOND_GOAL_ID = '22222222-2222-2222-2222-222222222222'
const CONTRIBUTION_ID = '33333333-3333-3333-3333-333333333333'
const TRANSACTION_CONTRIBUTION_ID = '44444444-4444-4444-4444-444444444444'

function goal(overrides = {}) {
  return {
    id: GOAL_ID,
    name: 'Fonds de sécurité',
    description: 'Une réserve rassurante',
    type: 'EMERGENCY_FUND',
    currency: 'TND',
    targetAmount: 1000,
    initialAmount: 100,
    contributionTotal: 200,
    currentSaved: 300,
    remainingAmount: 700,
    progressPercentage: 30,
    targetDate: '2027-08-31',
    overdue: false,
    priority: 'HIGH',
    status: 'ACTIVE',
    createdAt: '2026-08-01T10:00:00Z',
    updatedAt: '2026-08-01T10:00:00Z',
    version: 0,
    ...overrides,
  }
}

function allocationOverview(overrides = {}) {
  return {
    month: DEFAULT_MONTH,
    currency: 'TND',
    capacityAvailable: true,
    salaryPlanSavingCapacity: 600,
    allocatableCapacity: 600,
    totalConfirmedAllocation: 200,
    remainingUnallocatedCapacity: 400,
    allocations: [{
      goalId: GOAL_ID,
      goalName: 'Fonds de sécurité',
      goalStatus: 'ACTIVE',
      priority: 'HIGH',
      allocatedAmount: 200,
      allocationStatus: 'CONFIRMED',
      projection: {
        remainingGoalAmount: 700,
        monthsToTarget: 4,
        estimatedAchievementDate: '2026-12-31',
        reachable: true,
        guaranteed: false,
      },
    }],
    ...overrides,
  }
}

function contribution(overrides = {}) {
  return {
    id: CONTRIBUTION_ID,
    amount: 100,
    contributionDate: '2026-08-10',
    source: 'MANUAL',
    createdAt: '2026-08-10T10:00:00Z',
    ...overrides,
  }
}

function renderPage() {
  return render(<MemoryRouter><GoalsPage /></MemoryRouter>)
}

function deferred() {
  let resolve
  let reject
  const promise = new Promise((promiseResolve, promiseReject) => {
    resolve = promiseResolve
    reject = promiseReject
  })
  return { promise, resolve, reject }
}

describe('Goals connectés', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    window.confirm = vi.fn(() => true)
    listGoals.mockResolvedValue([goal()])
    getGoalAllocations.mockResolvedValue(allocationOverview())
    listGoalContributions.mockResolvedValue([])
    createGoal.mockResolvedValue(goal())
    updateGoal.mockResolvedValue(goal())
    changeGoalStatus.mockResolvedValue(goal())
    archiveGoal.mockResolvedValue(null)
    addGoalContribution.mockResolvedValue(goal())
    deleteGoalContribution.mockResolvedValue(goal())
    replaceGoalAllocations.mockResolvedValue(allocationOverview())
    simulateGoal.mockResolvedValue({
      goalId: GOAL_ID,
      currency: 'TND',
      calculationDate: '2026-08-13',
      currentSaved: 300,
      remainingAmount: 700,
      simulatedMonthlyContribution: 200,
      monthsToTarget: 4,
      estimatedAchievementDate: '2026-12-31',
      reachable: true,
      targetDate: '2027-08-31',
      meetsTargetDate: true,
      method: 'DETERMINISTIC_RECURRING_CONTRIBUTION',
      methodVersion: '1',
    })
  })

  it('charge les objectifs et la répartition réels pour le mois et la devise exacts', async () => {
    renderPage()

    expect(await screen.findByRole('heading', { name: 'Fonds de sécurité' })).toBeInTheDocument()
    expect(screen.getByText('Fonds d’urgence')).toBeInTheDocument()
    expect(screen.getByText('Reste à épargner')).toBeInTheDocument()
    expect(screen.getByText(/Date estimée/)).toBeInTheDocument()
    expect(screen.getByText(/sans garantie/)).toBeInTheDocument()
    expect(listGoals).toHaveBeenCalledWith('TND', false)
    expect(getGoalAllocations).toHaveBeenCalledWith(DEFAULT_MONTH, 'TND')
  })

  it('distingue une erreur de liste, une erreur de capacité et les vrais états vides', async () => {
    listGoals.mockRejectedValueOnce(new ApiError(503, 'Unavailable'))
    getGoalAllocations.mockResolvedValueOnce(allocationOverview())
    const first = renderPage()

    expect(await screen.findByText('La liste de vos objectifs n’a pas pu être chargé pour le moment.')).toBeInTheDocument()
    expect(screen.queryByText('Aucun objectif dans cette devise')).not.toBeInTheDocument()
    expect(screen.getByText('Capacité répartissable')).toBeInTheDocument()
    first.unmount()

    listGoals.mockResolvedValueOnce([])
    getGoalAllocations.mockRejectedValueOnce(new ApiError(503, 'Unavailable'))
    renderPage()

    expect(await screen.findByText('Aucun objectif dans cette devise')).toBeInTheDocument()
    expect(screen.getByText('La répartition mensuelle n’a pas pu être chargé pour le moment.')).toBeInTheDocument()
    expect(screen.queryByText('Aucune capacité n’est disponible.')).not.toBeInTheDocument()
  })

  it('ignore les anciennes réponses après un changement rapide de devise', async () => {
    const oldGoals = deferred()
    const newGoals = deferred()
    const oldAllocation = deferred()
    const newAllocation = deferred()
    listGoals.mockReturnValueOnce(oldGoals.promise).mockReturnValueOnce(newGoals.promise)
    getGoalAllocations.mockReturnValueOnce(oldAllocation.promise).mockReturnValueOnce(newAllocation.promise)
    renderPage()

    fireEvent.change(screen.getByLabelText('Devise'), { target: { value: 'USD' } })
    await act(async () => {
      newGoals.resolve([goal({ id: SECOND_GOAL_ID, name: 'Projet en dollars', currency: 'USD' })])
      newAllocation.resolve(allocationOverview({ currency: 'USD', allocations: [], totalConfirmedAllocation: 0, remainingUnallocatedCapacity: 600 }))
    })
    expect(await screen.findByRole('heading', { name: 'Projet en dollars' })).toBeInTheDocument()

    await act(async () => {
      oldGoals.resolve([goal({ name: 'Ancien résultat TND' })])
      oldAllocation.resolve(allocationOverview())
    })
    expect(screen.queryByText('Ancien résultat TND')).not.toBeInTheDocument()
    expect(screen.getByText('Vos objectifs en USD')).toBeInTheDocument()
  })

  it('crée un objectif avec le payload minimal puis affiche la réponse serveur autoritaire', async () => {
    const saved = goal({ id: SECOND_GOAL_ID, name: 'Voyage familial', currentSaved: 125, progressPercentage: 12.5 })
    createGoal.mockResolvedValueOnce(saved)
    renderPage()
    await screen.findByRole('heading', { name: 'Fonds de sécurité' })

    fireEvent.click(screen.getByRole('button', { name: 'Créer un objectif' }))
    fireEvent.change(screen.getByLabelText('Nom de l’objectif'), { target: { value: '  Voyage familial  ' } })
    fireEvent.change(screen.getByLabelText('Description facultative'), { target: { value: '  Vacances ensemble  ' } })
    fireEvent.change(screen.getByLabelText('Type de projet'), { target: { value: 'TRAVEL' } })
    fireEvent.change(screen.getByLabelText('Montant cible (TND)'), { target: { value: '1000.0000' } })
    fireEvent.change(screen.getByLabelText('Montant déjà épargné (TND)'), { target: { value: '100.0000' } })
    fireEvent.change(screen.getByLabelText('Date cible facultative'), { target: { value: '2027-08-31' } })
    fireEvent.change(screen.getByLabelText('Priorité'), { target: { value: 'HIGH' } })
    fireEvent.click(screen.getByRole('button', { name: 'Créer mon objectif' }))

    await waitFor(() => expect(createGoal).toHaveBeenCalledWith({
      name: 'Voyage familial',
      description: 'Vacances ensemble',
      type: 'TRAVEL',
      currency: 'TND',
      targetAmount: 1000,
      initialAmount: 100,
      targetDate: '2027-08-31',
      priority: 'HIGH',
    }))
    expect(await screen.findByText('Votre objectif a bien été créé.')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Voyage familial' })).toBeInTheDocument()
    expect(screen.getByText(/125,000/)).toBeInTheDocument()
  })

  it('valide en français puis gère modification, pause, reprise, annulation et archivage', async () => {
    updateGoal.mockResolvedValueOnce(goal({ name: 'Réserve familiale' }))
    changeGoalStatus
      .mockResolvedValueOnce(goal({ name: 'Réserve familiale', status: 'PAUSED' }))
      .mockResolvedValueOnce(goal({ name: 'Réserve familiale', status: 'ACTIVE' }))
      .mockResolvedValueOnce(goal({ name: 'Réserve familiale', status: 'CANCELLED' }))
    renderPage()
    await screen.findByRole('heading', { name: 'Fonds de sécurité' })

    fireEvent.click(screen.getByRole('button', { name: 'Modifier' }))
    fireEvent.change(screen.getByLabelText('Nom de l’objectif'), { target: { value: ' ' } })
    fireEvent.change(screen.getByLabelText('Montant cible (TND)'), { target: { value: '0' } })
    fireEvent.change(screen.getByLabelText('Montant déjà épargné (TND)'), { target: { value: '1.00001' } })
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer les modifications' }))
    expect(screen.getByText('Donnez un nom à votre objectif.')).toBeInTheDocument()
    expect(screen.getByText('Le montant doit être strictement positif.')).toBeInTheDocument()
    expect(screen.getByText('Utilisez au maximum quatre chiffres après la virgule.')).toBeInTheDocument()
    expect(updateGoal).not.toHaveBeenCalled()

    fireEvent.change(screen.getByLabelText(/Nom de l’objectif/), { target: { value: 'Réserve familiale' } })
    fireEvent.change(screen.getByLabelText(/Montant cible \(TND\)/), { target: { value: '1000' } })
    fireEvent.change(screen.getByLabelText(/Montant déjà épargné \(TND\)/), { target: { value: '100' } })
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer les modifications' }))
    expect(await screen.findByRole('heading', { name: 'Réserve familiale' })).toBeInTheDocument()
    expect(updateGoal.mock.calls[0][1]).not.toHaveProperty('id')
    expect(updateGoal.mock.calls[0][1]).not.toHaveProperty('status')
    expect(screen.getByRole('button', { name: 'Modifier' })).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Mettre en pause' }))
    expect(await screen.findByRole('button', { name: 'Reprendre' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Reprendre' }))
    expect(await screen.findByRole('button', { name: 'Mettre en pause' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Annuler' }))
    await waitFor(() => expect(changeGoalStatus).toHaveBeenLastCalledWith(GOAL_ID, 'CANCELLED'))
    expect(screen.queryByRole('button', { name: 'Modifier' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Reprendre' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Archiver' }))
    await waitFor(() => expect(archiveGoal).toHaveBeenCalledWith(GOAL_ID))
    expect(screen.queryByRole('heading', { name: 'Réserve familiale' })).not.toBeInTheDocument()
  })

  it('ajoute et supprime les versements manuels, sans proposer la suppression liée à une transaction', async () => {
    const manual = contribution()
    const transactionLinked = contribution({ id: TRANSACTION_CONTRIBUTION_ID, source: 'TRANSACTION', amount: 50 })
    listGoalContributions.mockResolvedValue([manual, transactionLinked])
    addGoalContribution.mockResolvedValueOnce(goal({ currentSaved: 400, remainingAmount: 600, progressPercentage: 40 }))
    deleteGoalContribution.mockResolvedValueOnce(goal({ currentSaved: 300, remainingAmount: 700, progressPercentage: 30 }))
    renderPage()
    const goalHeading = await screen.findByRole('heading', { name: 'Fonds de sécurité' })
    const goalCard = goalHeading.closest('.goal-card')

    fireEvent.click(screen.getByRole('button', { name: 'Voir le détail' }))
    expect(await screen.findByText(/Versement manuel/)).toBeInTheDocument()
    const transactionRow = screen.getByText(/Lié à une transaction/).closest('li')
    expect(within(transactionRow).queryByRole('button', { name: 'Supprimer' })).not.toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: 'Supprimer' })).toHaveLength(1)

    fireEvent.change(screen.getByLabelText('Nouveau versement (TND)'), { target: { value: '100.0000' } })
    fireEvent.change(screen.getByLabelText('Date facultative'), { target: { value: '2026-08-10' } })
    fireEvent.click(screen.getByRole('button', { name: 'Ajouter le versement' }))
    await waitFor(() => expect(addGoalContribution).toHaveBeenCalledWith(GOAL_ID, {
      amount: 100,
      contributionDate: '2026-08-10',
    }))
    expect(await within(goalCard).findByText(/400,000/)).toBeInTheDocument()

    fireEvent.click(screen.getAllByRole('button', { name: 'Supprimer' })[0])
    await waitFor(() => expect(deleteGoalContribution).toHaveBeenCalledWith(GOAL_ID, CONTRIBUTION_ID))
    expect(within(goalCard).getByText(/300,000/)).toBeInTheDocument()
  })

  it('affiche absence de plan, déficit, capacité nulle et lecture seule historique sans les confondre', async () => {
    getGoalAllocations.mockResolvedValueOnce(allocationOverview({
      capacityAvailable: false,
      salaryPlanSavingCapacity: null,
      allocatableCapacity: 0,
      totalConfirmedAllocation: 0,
      remainingUnallocatedCapacity: 0,
      allocations: [],
    }))
    const first = renderPage()
    expect(await screen.findByText(/Aucun plan de salaire n’existe/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Préparer mon Salary Planning' })).toHaveAttribute('href', '/planification-salaire')
    expect(screen.getByRole('button', { name: 'Enregistrer la répartition' })).toBeDisabled()
    first.unmount()

    getGoalAllocations.mockResolvedValueOnce(allocationOverview({
      salaryPlanSavingCapacity: -150,
      allocatableCapacity: 0,
      totalConfirmedAllocation: 0,
      remainingUnallocatedCapacity: 0,
      allocations: [],
    }))
    const second = renderPage()
    expect(await screen.findByText(/présente un déficit de/)).toBeInTheDocument()
    expect(screen.getByText(/Vous pouvez consulter vos objectifs/)).toBeInTheDocument()
    second.unmount()

    getGoalAllocations.mockResolvedValueOnce(allocationOverview({
      salaryPlanSavingCapacity: 0,
      allocatableCapacity: 0,
      totalConfirmedAllocation: 0,
      remainingUnallocatedCapacity: 0,
      allocations: [],
    }))
    const third = renderPage()
    expect(await screen.findByText(/capacité d’épargne est nulle/)).toBeInTheDocument()
    third.unmount()

    const pastMonth = shiftMonth(DEFAULT_MONTH, -1)
    getGoalAllocations.mockResolvedValueOnce(allocationOverview({ month: pastMonth }))
    renderPage()
    await screen.findByRole('heading', { name: 'Fonds de sécurité' })
    fireEvent.change(screen.getByLabelText('Mois de répartition'), { target: { value: pastMonth } })
    expect(await screen.findByText('Lecture seule')).toBeInTheDocument()
    expect(screen.getByLabelText('Fonds de sécurité (TND)')).toBeDisabled()
  })

  it('contrôle le brouillon scale-4, empêche le dépassement et remplace son état par la réponse PUT', async () => {
    const saved = allocationOverview({
      totalConfirmedAllocation: 250,
      remainingUnallocatedCapacity: 350,
      allocations: [{
        ...allocationOverview().allocations[0],
        allocatedAmount: 250,
      }],
    })
    replaceGoalAllocations.mockResolvedValueOnce(saved)
    renderPage()
    const input = await screen.findByLabelText('Fonds de sécurité (TND)')

    fireEvent.change(input, { target: { value: '700.0000' } })
    expect(screen.getByText('Le total estimé dépasse la capacité répartissable.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Enregistrer la répartition' })).toBeDisabled()
    expect(replaceGoalAllocations).not.toHaveBeenCalled()

    fireEvent.change(input, { target: { value: '200.00001' } })
    expect(screen.getByText('Utilisez au maximum quatre chiffres après la virgule.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Enregistrer la répartition' })).toBeDisabled()
    expect(replaceGoalAllocations).not.toHaveBeenCalled()

    fireEvent.change(input, { target: { value: '200.0000' } })
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer la répartition' }))
    await waitFor(() => expect(replaceGoalAllocations).toHaveBeenCalledWith(DEFAULT_MONTH, 'TND', [{
      goalId: GOAL_ID,
      amount: 200,
    }]))
    expect(await screen.findByText('Votre répartition mensuelle a bien été enregistrée.')).toBeInTheDocument()
    expect(screen.getByLabelText('Fonds de sécurité (TND)')).toHaveValue('250')
    expect(screen.getByText(/Total confirmé/).parentElement).toHaveTextContent(/250,000/)
  })

  it('envoie une liste vide pour effacer les allocations éditables', async () => {
    replaceGoalAllocations.mockResolvedValueOnce(allocationOverview({
      totalConfirmedAllocation: 0,
      remainingUnallocatedCapacity: 600,
      allocations: [{ ...allocationOverview().allocations[0], allocationStatus: 'CANCELLED', projection: null }],
    }))
    renderPage()
    const input = await screen.findByLabelText('Fonds de sécurité (TND)')
    fireEvent.change(input, { target: { value: '0' } })
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer la répartition' }))

    await waitFor(() => expect(replaceGoalAllocations).toHaveBeenCalledWith(DEFAULT_MONTH, 'TND', []))
    expect(window.confirm).toHaveBeenCalledWith('Retirer toutes les allocations de ce mois ?')
    expect(screen.getByText(/Annulée/)).toBeInTheDocument()
  })

  it('simule un rythme atteignable puis nul sans scénario enregistré ni persistance locale', async () => {
    const storageSpy = vi.spyOn(Storage.prototype, 'setItem')
    simulateGoal
      .mockResolvedValueOnce({
        goalId: GOAL_ID,
        currency: 'TND',
        calculationDate: '2026-08-13',
        currentSaved: 300,
        remainingAmount: 700,
        simulatedMonthlyContribution: 200,
        monthsToTarget: 4,
        estimatedAchievementDate: '2026-12-31',
        reachable: true,
        targetDate: '2027-08-31',
        meetsTargetDate: true,
        method: 'DETERMINISTIC_RECURRING_CONTRIBUTION',
        methodVersion: '1',
      })
      .mockResolvedValueOnce({
        goalId: GOAL_ID,
        currency: 'TND',
        calculationDate: '2026-08-13',
        currentSaved: 300,
        remainingAmount: 700,
        simulatedMonthlyContribution: 0,
        monthsToTarget: null,
        estimatedAchievementDate: null,
        reachable: false,
        targetDate: '2027-08-31',
        meetsTargetDate: false,
        method: 'DETERMINISTIC_RECURRING_CONTRIBUTION',
        methodVersion: '1',
      })
    renderPage()
    await screen.findByRole('heading', { name: 'Fonds de sécurité' })
    fireEvent.click(screen.getByRole('button', { name: 'Voir le détail' }))

    const input = screen.getByLabelText('Contribution mensuelle simulée (TND)')
    fireEvent.change(input, { target: { value: '200.0000' } })
    fireEvent.click(screen.getByRole('button', { name: 'Calculer la projection' }))
    await waitFor(() => expect(simulateGoal).toHaveBeenCalledWith(GOAL_ID, 200))
    expect(await screen.findByText('Date d’atteinte estimée')).toBeInTheDocument()
    expect(screen.getByText('Projection compatible')).toBeInTheDocument()
    expect(screen.getAllByText(/sans garantie/).length).toBeGreaterThan(0)

    fireEvent.change(input, { target: { value: '0' } })
    fireEvent.click(screen.getByRole('button', { name: 'Calculer la projection' }))
    await waitFor(() => expect(simulateGoal).toHaveBeenLastCalledWith(GOAL_ID, 0))
    expect(await screen.findByText('Objectif non atteignable avec ce rythme')).toBeInTheDocument()
    expect(screen.getByText('Projection au-delà de la cible')).toBeInTheDocument()
    expect(screen.queryByText(/Scénarios enregistrés/i)).not.toBeInTheDocument()
    expect(storageSpy).not.toHaveBeenCalled()
  })

  it('recharge seulement la capacité par mois, puis objectifs et capacité par devise, avec filtre archivé', async () => {
    const nextMonth = shiftMonth(DEFAULT_MONTH, 1)
    listGoals.mockImplementation((selectedCurrency, archived) => Promise.resolve(archived
      ? [goal(), goal({ id: SECOND_GOAL_ID, name: 'Ancien objectif', status: 'ARCHIVED' })]
      : [goal({ currency: selectedCurrency })]))
    getGoalAllocations.mockImplementation((selectedMonth, selectedCurrency) => Promise.resolve(allocationOverview({
      month: selectedMonth,
      currency: selectedCurrency,
      allocations: [],
    })))
    renderPage()
    await screen.findByRole('heading', { name: 'Fonds de sécurité' })

    fireEvent.change(screen.getByLabelText('Fonds de sécurité (TND)'), { target: { value: '250' } })
    window.confirm.mockReturnValueOnce(false)
    fireEvent.change(screen.getByLabelText('Mois de répartition'), { target: { value: nextMonth } })
    expect(getGoalAllocations).not.toHaveBeenCalledWith(nextMonth, 'TND')
    expect(window.confirm).toHaveBeenCalledWith('Abandonner la répartition non enregistrée ?')

    window.confirm.mockReturnValueOnce(true)
    fireEvent.change(screen.getByLabelText('Mois de répartition'), { target: { value: nextMonth } })
    await waitFor(() => expect(getGoalAllocations).toHaveBeenCalledWith(nextMonth, 'TND'))
    expect(listGoals).toHaveBeenCalledTimes(1)

    fireEvent.change(screen.getByLabelText('Devise'), { target: { value: 'USD' } })
    await waitFor(() => expect(listGoals).toHaveBeenCalledWith('USD', false))
    expect(getGoalAllocations).toHaveBeenCalledWith(nextMonth, 'USD')

    fireEvent.click(screen.getByLabelText('Afficher les objectifs archivés'))
    await waitFor(() => expect(listGoals).toHaveBeenCalledWith('USD', true))
    expect(await screen.findByRole('heading', { name: 'Ancien objectif' })).toBeInTheDocument()
  })

  it('affiche clairement les états en déficit et en retard', async () => {
    listGoals.mockResolvedValueOnce([goal({ overdue: true, targetDate: '2025-01-31' })])
    getGoalAllocations.mockResolvedValueOnce(allocationOverview({
      salaryPlanSavingCapacity: -125,
      allocatableCapacity: 0,
      totalConfirmedAllocation: 0,
      remainingUnallocatedCapacity: 0,
      allocations: [],
    }))
    renderPage()

    expect(await screen.findByText(/date cible est dépassée/)).toBeInTheDocument()
    expect(screen.getByText(/présente un déficit de/)).toBeInTheDocument()
    expect(screen.getAllByText(/125,000/).length).toBeGreaterThan(0)
  })
})
